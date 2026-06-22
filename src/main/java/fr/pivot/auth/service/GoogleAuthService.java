package fr.pivot.auth.service;

import com.google.api.client.googleapis.auth.oauth2.GoogleIdToken;
import com.google.api.client.googleapis.auth.oauth2.GoogleIdTokenVerifier;
import com.google.api.client.http.javanet.NetHttpTransport;
import com.google.api.client.json.gson.GsonFactory;
import fr.pivot.auth.dto.AuthResponse;
import fr.pivot.auth.dto.GoogleAuthRequest;
import fr.pivot.auth.entity.AuthMethod;
import fr.pivot.auth.entity.User;
import fr.pivot.auth.mapper.UserMapper;
import fr.pivot.auth.repository.UserRepository;
import fr.pivot.tenant.entity.Tenant;
import fr.pivot.tenant.repository.TenantRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.security.GeneralSecurityException;
import java.time.Duration;
import java.util.Collections;

/**
 * Handles Google Sign-In (OAuth2 ID token) authentication.
 *
 * <p>Angular obtains a Google ID token via the Google Sign-In SDK, then sends it here
 * for verification. On success, issues an opaque session token via {@link TokenService}.
 *
 * <p>Google SSO sessions use TTL=standard (no «se souvenir de moi» — Google manages
 * its own session persistence on the IdP side).
 */
@Service
public class GoogleAuthService {

    private final UserRepository userRepo;
    private final TenantRepository tenantRepo;
    private final TokenService tokenService;
    private final AuditService auditService;
    private final TrustedDeviceService trustedDeviceService;
    private final RateLimiterService rateLimiter;

    /**
     * Reused across requests — building a new verifier per login defeats the Google public-key
     * cache (re-fetching certs every time). Thread-safe once built.
     */
    private final GoogleIdTokenVerifier verifier;

    /**
     * Constructs the service with its required collaborators.
     *
     * @param userRepo           JPA repository for users
     * @param tenantRepo         JPA repository for tenants
     * @param tokenService       opaque session token lifecycle manager
     * @param auditService       async audit event logger
     * @param trustedDeviceService manages trusted device records
     * @param rateLimiter        sliding-window rate limiter backed by Redis
     * @param googleClientId     OAuth2 client ID from Google Cloud Console
     */
    public GoogleAuthService(
            final UserRepository userRepo,
            final TenantRepository tenantRepo,
            final TokenService tokenService,
            final AuditService auditService,
            final TrustedDeviceService trustedDeviceService,
            final RateLimiterService rateLimiter,
            @Value("${spring.security.oauth2.client.registration.google.client-id:}") final String googleClientId) {
        this.userRepo = userRepo;
        this.tenantRepo = tenantRepo;
        this.tokenService = tokenService;
        this.auditService = auditService;
        this.trustedDeviceService = trustedDeviceService;
        this.rateLimiter = rateLimiter;
        this.verifier = new GoogleIdTokenVerifier.Builder(
                new NetHttpTransport(), GsonFactory.getDefaultInstance())
            .setAudience(Collections.singletonList(googleClientId))
            .build();
    }

    /**
     * Verifies a Google ID token and returns an opaque session token.
     *
     * <p>Creates a new user account if none exists for the Google subject ID.
     * Links an existing account by email if the Google ID is new but the email matches.
     *
     * @param req       Google auth payload (ID token, optional device fingerprint/name)
     * @param ip        client IP for rate limiting and audit
     * @param userAgent browser user-agent for audit and device metadata
     * @return {@link GoogleLoginResult} containing the session token and user info
     * @throws ResponseStatusException 401 on invalid Google token, 403 on blocked account,
     *     429 on rate limit
     */
    @Transactional
    public GoogleLoginResult authenticate(final GoogleAuthRequest req, final String ip, final String userAgent) {
        if (!rateLimiter.checkAndRecord("google:ip:" + ip, 20, Duration.ofMinutes(15))) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS);
        }

        final GoogleIdToken.Payload payload = verifyGoogleToken(req.idToken());
        final String googleId = payload.getSubject();
        final String email = payload.getEmail().toLowerCase();
        final String firstName = (String) payload.get("given_name");
        final String lastName = (String) payload.get("family_name");
        final String avatarUrl = (String) payload.get("picture");

        final Tenant tenant = tenantRepo.findBySlug("pivot-saas")
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR));

        User user = userRepo.findByGoogleIdAndDeletedAtIsNull(googleId).orElse(null);

        if (user == null) {
            final User byEmail = userRepo.findByTenantIdAndEmailAndDeletedAtIsNull(tenant.getId(), email).orElse(null);
            if (byEmail != null) {
                byEmail.setGoogleId(googleId);
                user = userRepo.save(byEmail);
                auditService.log(user, AuditService.GOOGLE_LINKED, ip, userAgent);
            } else {
                user = new User();
                user.setTenant(tenant);
                user.setEmail(email);
                user.setGoogleId(googleId);
                user.setFirstName(firstName);
                user.setLastName(lastName);
                user.setAvatarUrl(avatarUrl);
                user.setEmailVerified(true);
                user = userRepo.save(user);
            }
        }

        if (!user.isActive() || user.isBlocked()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Compte désactivé");
        }

        if (req.deviceFingerprint() != null && !req.deviceFingerprint().isBlank()) {
            trustedDeviceService.trust(user, req.deviceFingerprint(), req.deviceName());
        }

        final boolean isNewAccount = user.getLastLoginAt() == null;
        userRepo.updateLastLoginAt(user.getId());

        final TokenService.TokenIssueResult issued = tokenService.issue(
            user, req.deviceFingerprint(), req.deviceName(), userAgent, ip, AuthMethod.GOOGLE, false);

        auditService.log(user, AuditService.LOGIN, ip, userAgent);
        return new GoogleLoginResult(
            issued.rawToken(),
            issued.expiresAt().toEpochMilli(),
            issued.ttlSeconds(),
            UserMapper.toUserInfo(user),
            isNewAccount);
    }

    // ----------------------------------------------------------------
    // Private helpers
    // ----------------------------------------------------------------

    private GoogleIdToken.Payload verifyGoogleToken(final String idTokenStr) {
        try {
            final GoogleIdToken token = verifier.verify(idTokenStr);
            if (token == null) {
                throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Token Google invalide");
            }
            return token.getPayload();
        } catch (GeneralSecurityException | IOException _) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Token Google invalide");
        }
    }

    // ----------------------------------------------------------------
    // Result record
    // ----------------------------------------------------------------

    /**
     * Carries the session token and user metadata returned after Google authentication.
     *
     * @param sessionToken  raw opaque token — send to client as Bearer + set in session cookie
     * @param expiresAt     epoch-millisecond timestamp of token expiry
     * @param ttlSeconds    TTL in seconds (for cookie {@code Max-Age})
     * @param user          authenticated user information
     * @param isNewAccount  {@code true} if a new account was created for this Google login
     */
    public record GoogleLoginResult(
        String sessionToken,
        long expiresAt,
        int ttlSeconds,
        AuthResponse.UserInfo user,
        boolean isNewAccount) {}
}

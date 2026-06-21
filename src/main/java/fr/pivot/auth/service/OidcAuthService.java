package fr.pivot.auth.service;

import fr.pivot.auth.dto.AuthResponse;
import fr.pivot.auth.dto.OidcExchangeRequest;
import fr.pivot.auth.entity.AuthMethod;
import fr.pivot.auth.entity.User;
import fr.pivot.auth.mapper.UserMapper;
import fr.pivot.auth.repository.UserRepository;
import fr.pivot.tenant.entity.Tenant;
import fr.pivot.tenant.entity.TenantOidcConfig;
import fr.pivot.tenant.repository.TenantOidcConfigRepository;
import fr.pivot.tenant.repository.TenantRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtDecoders;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;

/**
 * Handles enterprise OIDC (OpenID Connect) authentication via the PKCE flow.
 *
 * <p>Angular performs the PKCE exchange against the enterprise IdP, then sends
 * the resulting access token here for verification against the tenant's JWKS endpoint.
 * On success, issues an opaque session token via {@link TokenService}.
 *
 * <p>Uses {@code spring-security-oauth2-jose} directly (no Spring Boot auto-config starter),
 * avoiding conflicts with the main opaque-token {@link fr.pivot.config.SecurityConfig}.
 * Each tenant has its own OIDC configuration ({@code TenantOidcConfig}) containing
 * the issuer URI, client ID and scopes.
 */
@Service
public class OidcAuthService {

    private static final Logger LOG = LoggerFactory.getLogger(OidcAuthService.class);

    private final TenantRepository tenantRepo;
    private final TenantOidcConfigRepository oidcConfigRepo;
    private final UserRepository userRepo;
    private final TokenService tokenService;
    private final AuditService auditService;
    private final TrustedDeviceService trustedDeviceService;
    private final RateLimiterService rateLimiter;

    /**
     * Constructs the service with its required collaborators.
     *
     * @param tenantRepo          JPA repository for tenants
     * @param oidcConfigRepo      JPA repository for per-tenant OIDC configurations
     * @param userRepo            JPA repository for users
     * @param tokenService        opaque session token lifecycle manager
     * @param auditService        async audit event logger
     * @param trustedDeviceService manages trusted device records
     * @param rateLimiter         sliding-window rate limiter backed by Redis
     */
    public OidcAuthService(
            final TenantRepository tenantRepo,
            final TenantOidcConfigRepository oidcConfigRepo,
            final UserRepository userRepo,
            final TokenService tokenService,
            final AuditService auditService,
            final TrustedDeviceService trustedDeviceService,
            final RateLimiterService rateLimiter) {
        this.tenantRepo = tenantRepo;
        this.oidcConfigRepo = oidcConfigRepo;
        this.userRepo = userRepo;
        this.tokenService = tokenService;
        this.auditService = auditService;
        this.trustedDeviceService = trustedDeviceService;
        this.rateLimiter = rateLimiter;
    }

    /**
     * Exchanges an enterprise IdP access token for a PIVOT opaque session token.
     *
     * <p>Validates the access token against the tenant's issuer via OIDC discovery.
     * Creates a new user account if {@code auto_provision_users} is enabled
     * and no existing account matches the OIDC subject.
     *
     * @param req       OIDC exchange payload (tenant slug, access token, optional device info)
     * @param ip        client IP for rate limiting and audit
     * @param userAgent browser user-agent for audit and device metadata
     * @return {@link OidcLoginResult} containing the session token and user info
     * @throws ResponseStatusException 401 on invalid OIDC token, 403 on blocked account,
     *     404 on unknown tenant or unconfigured OIDC, 429 on rate limit
     */
    @Transactional
    public OidcLoginResult exchange(final OidcExchangeRequest req, final String ip, final String userAgent) {
        if (!rateLimiter.checkAndRecord("oidc:ip:" + ip, 20, Duration.ofMinutes(15))) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS);
        }

        final Tenant tenant = tenantRepo.findBySlug(req.tenantSlug())
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Tenant introuvable"));

        final TenantOidcConfig config = oidcConfigRepo.findByTenantId(tenant.getId())
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "OIDC non configuré pour ce tenant"));

        final Jwt jwt;
        try {
            final JwtDecoder decoder = JwtDecoders.fromIssuerLocation(config.getIssuerUri());
            jwt = decoder.decode(req.accessToken());
        } catch (final Exception e) {
            LOG.warn("event=OIDC_TOKEN_INVALID tenant={} reason={}", req.tenantSlug(), e.getMessage());
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Token OIDC invalide");
        }

        final String subject = jwt.getSubject();
        final String email = jwt.getClaimAsString("email");
        final String firstName = jwt.getClaimAsString("given_name");
        final String lastName = jwt.getClaimAsString("family_name");

        if (email == null || email.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Email absent du token OIDC");
        }

        final User user = userRepo.findByTenantIdAndOidcSubjectAndDeletedAtIsNull(tenant.getId(), subject)
            .orElseGet(() -> {
                if (!config.isAutoProvisionUsers()) {
                    throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                        "Provisionnement automatique désactivé pour ce tenant");
                }
                return createOidcUser(tenant, subject, email.toLowerCase(), firstName, lastName);
            });

        if (!user.isActive() || user.isBlocked()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Compte désactivé");
        }

        if (req.deviceFingerprint() != null && !req.deviceFingerprint().isBlank()) {
            trustedDeviceService.trust(user, req.deviceFingerprint(), req.deviceName());
        }

        userRepo.updateLastLoginAt(user.getId());

        final TokenService.TokenIssueResult issued = tokenService.issue(
            user, req.deviceFingerprint(), req.deviceName(), userAgent, ip, AuthMethod.OIDC, false);

        auditService.log(user, AuditService.OIDC_LOGIN, ip, userAgent);
        LOG.info("event=OIDC_LOGIN_SUCCESS userId={} tenant={}", user.getId(), req.tenantSlug());
        return new OidcLoginResult(
            issued.rawToken(),
            issued.expiresAt().toEpochMilli(),
            issued.ttlSeconds(),
            UserMapper.toUserInfo(user));
    }

    /**
     * Returns the OIDC client configuration for a tenant.
     *
     * <p>Angular fetches this before starting the PKCE flow to discover
     * the IdP issuer URI, client ID and requested scopes.
     *
     * @param tenantSlug the tenant's URL slug
     * @return OIDC client config for the given tenant
     * @throws ResponseStatusException 404 if tenant or OIDC config not found
     */
    public OidcClientConfig getClientConfig(final String tenantSlug) {
        final Tenant tenant = tenantRepo.findBySlug(tenantSlug)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        final TenantOidcConfig config = oidcConfigRepo.findByTenantId(tenant.getId())
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "OIDC non configuré"));
        return new OidcClientConfig(config.getIssuerUri(), config.getClientId(), config.getScopes());
    }

    // ----------------------------------------------------------------
    // Private helpers
    // ----------------------------------------------------------------

    private User createOidcUser(final Tenant tenant, final String subject,
                                 final String email, final String firstName, final String lastName) {
        final User u = new User();
        u.setTenant(tenant);
        u.setOidcSubject(subject);
        u.setEmail(email);
        u.setFirstName(firstName);
        u.setLastName(lastName);
        u.setEmailVerified(true);
        return userRepo.save(u);
    }

    // ----------------------------------------------------------------
    // Result records
    // ----------------------------------------------------------------

    /**
     * Carries the session token and user metadata returned after OIDC authentication.
     *
     * @param sessionToken raw opaque token — send to client as Bearer + set in session cookie
     * @param expiresAt    epoch-millisecond timestamp of token expiry
     * @param ttlSeconds   TTL in seconds (for cookie {@code Max-Age})
     * @param user         authenticated user information
     */
    public record OidcLoginResult(
        String sessionToken,
        long expiresAt,
        int ttlSeconds,
        AuthResponse.UserInfo user) {}

    /**
     * OIDC provider configuration returned to Angular before the PKCE flow.
     *
     * @param issuerUri the IdP issuer URI (used for JWKS discovery)
     * @param clientId  the OAuth2 client ID registered with the IdP
     * @param scopes    space-separated list of OAuth2 scopes to request
     */
    public record OidcClientConfig(String issuerUri, String clientId, String scopes) {}
}

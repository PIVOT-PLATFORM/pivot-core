package fr.pivot.agilite.testsupport.auth;

import fr.pivot.agilite.auth.entity.PlatformUser;
import fr.pivot.agilite.auth.repository.PlatformUserReadRepository;
import fr.pivot.agilite.testsupport.auth.entity.PlatformAccessToken;
import fr.pivot.agilite.testsupport.auth.entity.PlatformTenant;
import fr.pivot.agilite.testsupport.auth.repository.AccessTokenReadRepository;
import fr.pivot.agilite.testsupport.auth.repository.PlatformTenantReadRepository;
import fr.pivot.core.auth.AuthenticatedPrincipal;
import fr.pivot.core.auth.AuthenticatedPrincipalResolver;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Optional;

/**
 * <strong>Test-only</strong> {@link AuthenticatedPrincipalResolver}, validating opaque bearer
 * tokens directly against {@code public.access_tokens}/{@code public.users}/{@code
 * public.tenants} (EN53.1 Vague 1).
 *
 * <p><strong>Why this exists, and why it is test-only.</strong> Before the modulith merge, this
 * module was a standalone Spring Boot application and its own production code duplicated {@code
 * pivot-core}'s opaque-token validation algorithm here (formerly {@code
 * fr.pivot.agilite.auth.TokenValidationService}, EN08.3/ADR-022 — the accepted pattern for a
 * genuinely standalone deployment, never a network call back to {@code pivot-core}). Now that
 * this module is aggregated into {@code pivot-core-app}'s single JVM/Spring context, that
 * production duplication has been removed entirely: {@code fr.pivot.auth.service.TokenService}
 * (the shell's own opaque-token service) is the sole production {@link
 * AuthenticatedPrincipalResolver} bean, and every consumer in this module ({@code
 * RequestPrincipalResolver}, {@code WheelChannelInterceptor}, {@code RetroSessionAccessService})
 * is wired against that shared interface, never against a concrete implementation — so they now
 * transparently authenticate through the shell's real token validation.
 *
 * <p>This class only backs this module's own <em>isolated</em> {@code @SpringBootTest} suite
 * ({@code fr.pivot.agilite.AgiliteTestApplication}, {@code @ComponentScan("fr.pivot.agilite")}):
 * that context has no dependency on {@code pivot-core-app}'s classes at all (this Maven module
 * only depends on {@code pivot-core-starter}), so without a local {@code
 * AuthenticatedPrincipalResolver} bean, {@code RequestPrincipalResolver}'s constructor injection
 * would fail at context startup with no candidate bean. Placed under {@code src/test/java} (never
 * compiled into the production library jar {@code pivot-core-app} depends on) so it can never be
 * mistaken for, or collide with, the shell's real bean once aggregated.
 *
 * <p>Duplicates — never calls over the network — the exact same validation algorithm as {@code
 * fr.pivot.auth.service.TokenService#validate(String)}: SHA-256 hash lookup, expiry check,
 * tenant-invalidation bulk-revocation check, and user-deactivation check. Kept byte-for-byte
 * equivalent so every existing {@code *IT} test in this module (including the cross-tenant
 * isolation tests) keeps testing the exact same security property, unaffected by which concrete
 * bean happens to sit behind {@link AuthenticatedPrincipalResolver} in a given runtime.
 *
 * <p><strong>Strictly read-only.</strong> Never writes to {@code access_tokens}/{@code users}/
 * {@code tenants} — the {@code last_used_at} throttled touch performed by the shell's real {@code
 * TokenService#validate} stays out of scope for this test double, exactly as it did for the
 * former production duplicate.
 *
 * <p><strong>Security.</strong> Never logs the raw token or its SHA-256 hash, at any level. Every
 * rejection reason (unknown token, expired, revoked, tenant deactivated, user deactivated)
 * collapses to the same {@link Optional#empty()} — the caller ({@code
 * fr.pivot.agilite.context.RequestPrincipalResolver}) maps that uniformly to a generic 401 with
 * no discriminating detail, so a caller can never distinguish these cases from the HTTP response.
 */
@Service
public class TestAuthenticatedPrincipalResolver implements AuthenticatedPrincipalResolver {

    /** Lowercase lifecycle status stored in {@code public.access_tokens.status} for a live token. */
    private static final String STATUS_ACTIVE = "active";

    private final AccessTokenReadRepository accessTokenRepository;
    private final PlatformUserReadRepository userRepository;
    private final PlatformTenantReadRepository tenantRepository;

    /**
     * Constructs the service with its read-only repositories.
     *
     * @param accessTokenRepository read-only access to {@code public.access_tokens}
     * @param userRepository        read-only access to {@code public.users} (shared with this
     *                               module's legitimate business lookups, e.g. wheel-entry
     *                               display-name resolution — not test-only)
     * @param tenantRepository      read-only access to {@code public.tenants}
     */
    public TestAuthenticatedPrincipalResolver(
            final AccessTokenReadRepository accessTokenRepository,
            final PlatformUserReadRepository userRepository,
            final PlatformTenantReadRepository tenantRepository) {
        this.accessTokenRepository = accessTokenRepository;
        this.userRepository = userRepository;
        this.tenantRepository = tenantRepository;
    }

    /**
     * Resolves a raw bearer token to the minimal {@link AuthenticatedPrincipal} of its owner.
     *
     * @param rawToken the raw opaque token extracted from the {@code Authorization: Bearer}
     *                 header, or {@code null}/blank
     * @return the resolved principal, or empty if the token is missing, unknown, expired,
     *     revoked, belongs to a deactivated user, or belongs to a deactivated tenant
     */
    @Override
    @Transactional(readOnly = true)
    public Optional<AuthenticatedPrincipal> resolve(final String rawToken) {
        if (rawToken == null || rawToken.isBlank()) {
            return Optional.empty();
        }

        final Optional<PlatformAccessToken> tokenOpt =
                accessTokenRepository.findByTokenHashAndStatus(sha256(rawToken), STATUS_ACTIVE);
        if (tokenOpt.isEmpty()) {
            return Optional.empty();
        }
        final PlatformAccessToken token = tokenOpt.get();
        if (!token.getExpiresAt().isAfter(Instant.now())) {
            return Optional.empty();
        }

        final Optional<PlatformUser> userOpt = userRepository.findById(token.getUserId());
        if (userOpt.isEmpty()) {
            return Optional.empty();
        }
        final PlatformUser user = userOpt.get();

        final Optional<PlatformTenant> tenantOpt = tenantRepository.findById(user.getTenantId());
        if (tenantOpt.isEmpty()) {
            return Optional.empty();
        }
        final PlatformTenant tenant = tenantOpt.get();

        if (isTenantInvalidated(token, tenant)) {
            return Optional.empty();
        }
        if (!user.isActive()) {
            return Optional.empty();
        }

        return Optional.of(new AuthenticatedPrincipal(user.getId(), tenant.getId(), user.getRole()));
    }

    /**
     * Checks whether {@code token} was issued at or before its tenant's last deactivation —
     * mirrors {@code fr.pivot.auth.service.TokenService#isTenantInvalidated}.
     *
     * @param token  the token being validated
     * @param tenant the token owner's tenant
     * @return {@code true} if the token must be rejected because its tenant was deactivated
     *     after it was issued
     */
    private boolean isTenantInvalidated(final PlatformAccessToken token, final PlatformTenant tenant) {
        final Instant invalidatedAt = tenant.getTenantInvalidationTimestamp();
        if (invalidatedAt == null) {
            return false;
        }
        return !token.getCreatedAt().isAfter(invalidatedAt);
    }

    /**
     * Returns the lowercase hex-encoded SHA-256 digest of {@code rawToken}.
     *
     * <p>Local helper, deliberately not shared with {@code pivot-core}'s {@code CryptoUtils} —
     * this test double never depends on {@code pivot-core-app}'s internal (non-starter)
     * packages. No new external crypto dependency: {@link MessageDigest} from the JDK is
     * sufficient.
     *
     * @param rawToken the plaintext bearer token — never logged, only hashed
     * @return 64-character hex string
     */
    private static String sha256(final String rawToken) {
        try {
            final MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(rawToken.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}

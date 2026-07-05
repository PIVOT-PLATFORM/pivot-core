package fr.pivot.auth.service;

import fr.pivot.auth.entity.AccessToken;
import fr.pivot.auth.entity.AuthMethod;
import fr.pivot.auth.entity.TokenStatus;
import fr.pivot.auth.entity.User;
import fr.pivot.auth.repository.AccessTokenRepository;
import fr.pivot.auth.repository.FeatureFlagRepository;
import fr.pivot.auth.util.CryptoUtils;
import fr.pivot.auth.util.HtmlStripper;
import fr.pivot.tenant.entity.Tenant;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.context.annotation.Lazy;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;

/**
 * Issues, validates and rotates DB-backed opaque session tokens (US-AUTH-002).
 *
 * <p>Replaces {@code JwtService}. No cryptographic secret is required — token security
 * relies on 256-bit SecureRandom entropy and SHA-256 storage in the database.
 *
 * <p>TTL and refresh threshold are read from {@code feature_flags} at runtime,
 * allowing admin-level configuration without redeployment:
 * <ul>
 *   <li>{@code SESSION_TTL_SECONDS} — standard session TTL (default 86400 = 24 h)</li>
 *   <li>{@code SESSION_TTL_REMEMBER_ME_SECONDS} — extended TTL (default 2592000 = 30 days)</li>
 *   <li>{@code SESSION_REFRESH_THRESHOLD} — refresh trigger ratio (default 0.5 = 50%)</li>
 * </ul>
 *
 * <p>Raw token format: 256 bits from SecureRandom, hex-encoded (64 hex chars). Stored in DB as SHA-256.
 */
@Service
public class TokenService {

    private static final Logger LOG = LoggerFactory.getLogger(TokenService.class);

    private static final int DEFAULT_TTL_SECONDS = 86_400;
    private static final int DEFAULT_TTL_REMEMBER_ME_SECONDS = 2_592_000;
    private static final double DEFAULT_REFRESH_THRESHOLD = 0.15;
    private static final int DEFAULT_ROTATION_GRACE_SECONDS = 30;

    /**
     * Max length of the persisted {@code device_name} — US02.2.3 (active sessions screen).
     * Applied together with {@link HtmlStripper} before every insert so the value stored in
     * {@code access_tokens} is always safe to render as plain text.
     */
    private static final int DEVICE_NAME_MAX_LENGTH = 200;

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();
    private static final HexFormat HEX = HexFormat.of();

    private final AccessTokenRepository tokenRepo;
    private final FeatureFlagRepository flagRepo;
    private final MeterRegistry meterRegistry;

    /**
     * Self-reference (proxied) used to invoke the {@code @Async} activity touch through the
     * Spring proxy — a direct call would bypass it and run synchronously.
     */
    private final TokenService self;

    private final long lastUsedThrottleSeconds;

    /**
     * Constructs the service with its required repositories and metrics registry.
     *
     * @param tokenRepo               JPA repository for {@link AccessToken}
     * @param flagRepo                JPA repository for {@link fr.pivot.auth.entity.FeatureFlag}
     * @param meterRegistry           Micrometer registry for operational metrics
     * @param self                    self proxy for async dispatch
     * @param lastUsedThrottleSeconds minimum seconds between two {@code last_used_at} writes
     */
    public TokenService(
            final AccessTokenRepository tokenRepo,
            final FeatureFlagRepository flagRepo,
            final MeterRegistry meterRegistry,
            final @Lazy TokenService self,
            @Value("${pivot.auth.last-used-throttle-seconds:60}") final long lastUsedThrottleSeconds) {
        this.tokenRepo = tokenRepo;
        this.flagRepo = flagRepo;
        this.meterRegistry = meterRegistry;
        this.self = self;
        this.lastUsedThrottleSeconds = lastUsedThrottleSeconds;
    }

    /**
     * Issues a new opaque session token for a user.
     *
     * <p>The TTL is read from {@code feature_flags} at call time. The resulting raw token
     * must be sent to the client immediately — it cannot be retrieved from the DB later.
     *
     * <p>Enforces {@code MAX_SESSIONS_PER_USER}: if the limit is reached, the oldest active
     * session is evicted (FIFO — oldest first) before creating the new one.
     *
     * @param user              the authenticated user
     * @param deviceFingerprint browser fingerprint (optional)
     * @param deviceName        human-readable device label (optional)
     * @param userAgent         browser user-agent string (optional)
     * @param ipAddress         client IP address for audit
     * @param authMethod        authentication method: {@code PASSWORD}, {@code GOOGLE}, {@code OIDC}
     * @param rememberMe        {@code true} to apply the extended TTL (30 days)
     * @return {@link TokenIssueResult} containing the raw token and expiry metadata
     */
    @Transactional
    public TokenIssueResult issue(
            final User user,
            final String deviceFingerprint,
            final String deviceName,
            final String userAgent,
            final String ipAddress,
            final AuthMethod authMethod,
            final boolean rememberMe) {
        return doIssue(user, deviceFingerprint, deviceName, userAgent, ipAddress, authMethod, rememberMe, true);
    }

    /**
     * Token creation logic shared by {@link #issue} and {@link #rotate}. Private so that
     * {@code rotate} reuses it within its own transaction without a self-proxy call
     * (Sonar S6809) — both public entry points are already {@code @Transactional}.
     *
     * @param enforceMaxSessions {@code true} for a genuine new session ({@code issue}); {@code false}
     *     on the rotation path. Rotation replaces an existing session — during the grace window the
     *     old token stays {@code ACTIVE}, so counting it toward {@code MAX_SESSIONS_PER_USER} would
     *     wrongly evict the oldest active token (the old token itself, cancelling the grace, or
     *     another healthy session). The old token expires at its grace deadline and the count
     *     self-corrects.
     */
    private TokenIssueResult doIssue(
            final User user,
            final String deviceFingerprint,
            final String deviceName,
            final String userAgent,
            final String ipAddress,
            final AuthMethod authMethod,
            final boolean rememberMe,
            final boolean enforceMaxSessions) {

        final int ttlSeconds = rememberMe
            ? flagRepo.getInt("SESSION_TTL_REMEMBER_ME_SECONDS", DEFAULT_TTL_REMEMBER_ME_SECONDS)
            : flagRepo.getInt("SESSION_TTL_SECONDS", DEFAULT_TTL_SECONDS);

        // Enforce MAX_SESSIONS_PER_USER — evict oldest session if limit reached.
        // Skipped on the rotation path so a rotation never consumes an extra logical slot.
        if (enforceMaxSessions) {
            final int maxSessions = flagRepo.getInt("MAX_SESSIONS_PER_USER", 10);
            final long activeSessions = tokenRepo.countByUserIdAndStatus(user.getId(), TokenStatus.ACTIVE);
            if (activeSessions >= maxSessions) {
                final List<AccessToken> oldest = tokenRepo.findOldestActiveByUserId(
                    user.getId(), TokenStatus.ACTIVE, PageRequest.of(0, 1));
                if (!oldest.isEmpty()) {
                    final AccessToken toEvict = oldest.get(0);
                    toEvict.setStatus(TokenStatus.REVOKED);
                    toEvict.setRevokedAt(Instant.now());
                    tokenRepo.save(toEvict);
                    LOG.warn("event=SESSION_EVICTED userId={} reason=max_sessions_reached limit={}", user.getId(), maxSessions);
                }
            }
        }

        final byte[] tokenBytes = new byte[32]; // 256 bits
        SECURE_RANDOM.nextBytes(tokenBytes);
        final String rawToken = HEX.formatHex(tokenBytes);

        final AccessToken token = new AccessToken();
        token.setUser(user);
        token.setTokenHash(CryptoUtils.sha256(rawToken));
        token.setDeviceFingerprint(deviceFingerprint);
        token.setDeviceName(HtmlStripper.stripAndTruncate(deviceName, DEVICE_NAME_MAX_LENGTH));
        token.setUserAgent(userAgent);
        token.setIpAddress(ipAddress);
        token.setAuthMethod(authMethod);
        token.setRememberMe(rememberMe);
        token.setStatus(TokenStatus.ACTIVE);
        token.setTtlSeconds(ttlSeconds);
        token.setExpiresAt(Instant.now().plusSeconds(ttlSeconds));
        tokenRepo.save(token);

        LOG.info("event=TOKEN_ISSUED userId={} authMethod={} rememberMe={} ttl={}s",
            user.getId(), authMethod, rememberMe, ttlSeconds);
        return new TokenIssueResult(rawToken, token.getExpiresAt(), ttlSeconds);
    }

    /**
     * Validates a raw bearer token and updates {@code last_used_at} (throttled to 60s).
     *
     * <p>If the token is valid, returns the persisted {@link AccessToken}. The caller
     * can then check {@link AccessToken#needsRefresh(double)} using the current threshold.
     *
     * @param rawToken the raw token extracted from the {@code Authorization: Bearer} header
     * @return the valid {@link AccessToken}, or empty if invalid / expired / revoked
     */
    @Transactional
    public Optional<AccessToken> validate(final String rawToken) {
        if (rawToken == null || rawToken.isBlank()) {
            return Optional.empty();
        }

        final String hash = CryptoUtils.sha256(rawToken);
        final Optional<AccessToken> opt = tokenRepo.findByTokenHashAndStatusWithUser(hash, TokenStatus.ACTIVE);

        if (opt.isEmpty()) {
            if (LOG.isWarnEnabled()) {
                LOG.warn("event=TOKEN_NOT_FOUND hash_prefix={}", hash.substring(0, 8));
            }
            meterRegistry.counter("pivot.auth.token.not_found").increment();
            return Optional.empty();
        }

        final AccessToken token = opt.get();
        if (!token.isValid()) {
            token.setStatus(TokenStatus.EXPIRED);
            tokenRepo.save(token);
            LOG.warn("event=TOKEN_EXPIRED userId={}", token.getUser() != null ? token.getUser().getId() : "?");
            return Optional.empty();
        }

        if (isTenantInvalidated(token)) {
            LOG.warn("event=TOKEN_REJECTED_TENANT_DEACTIVATED userId={} tenantId={}",
                token.getUser().getId(), token.getUser().getTenant().getId());
            meterRegistry.counter("pivot.auth.token.tenant_invalidated").increment();
            return Optional.empty();
        }

        // Throttle last_used_at updates — only write if null or beyond the throttle window.
        // Dispatched asynchronously (REQUIRES_NEW UPDATE-by-id) so this validation path never
        // opens a write transaction on the request thread (no per-request write amplification).
        final Instant now = Instant.now();
        final boolean shouldUpdate = token.getLastUsedAt() == null
            || java.time.Duration.between(token.getLastUsedAt(), now).getSeconds() > lastUsedThrottleSeconds;
        if (shouldUpdate) {
            self.touchLastUsed(token.getId(), now);
        }

        LOG.debug("event=TOKEN_VALID userId={}", token.getUser() != null ? token.getUser().getId() : "?");
        return Optional.of(token);
    }

    /**
     * Checks whether {@code token} was issued at or before its tenant's last deactivation
     * (US06.2.2 « Super admin désactive un tenant »).
     *
     * <p>Bulk revocation strategy: deactivating a tenant stamps a single
     * {@code tenant_invalidation_timestamp} on the {@code Tenant} row (O(1)) instead of
     * revoking every {@code AccessToken} row of every user of that tenant (which would be
     * O(n) users and could exceed the 500ms revocation SLA on a large tenant). Every token
     * validation must therefore re-check this condition — a token issued strictly after the
     * timestamp remains valid; one issued at or before it is treated as revoked.
     *
     * <p>{@code null} timestamp (tenant never deactivated) or a token with no resolvable
     * user/tenant (defensive — should not happen for a persisted token given the
     * {@code NOT NULL} FK constraints) both mean "not invalidated" — never blocks a
     * legitimate token.
     *
     * @param token the token being validated, with its user and tenant eagerly loaded
     * @return {@code true} if the token must be rejected because its tenant was deactivated
     *     after it was issued
     */
    private boolean isTenantInvalidated(final AccessToken token) {
        final User user = token.getUser();
        if (user == null) {
            return false;
        }
        final Tenant tenant = user.getTenant();
        if (tenant == null) {
            return false;
        }
        final Instant invalidatedAt = tenant.getTenantInvalidationTimestamp();
        if (invalidatedAt == null) {
            return false;
        }
        return !token.getCreatedAt().isAfter(invalidatedAt);
    }

    /**
     * Asynchronously records a token's last activity timestamp in its own transaction.
     *
     * @param tokenId the token primary key
     * @param when    the activity timestamp
     */
    @Async
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void touchLastUsed(final Long tokenId, final Instant when) {
        tokenRepo.updateLastUsedAt(tokenId, when);
    }

    /**
     * Returns the admin-configured refresh threshold.
     *
     * @return threshold ratio (0.0–1.0) from {@code SESSION_REFRESH_THRESHOLD} flag
     */
    @Cacheable(cacheNames = "feature-flags", key = "'session-refresh-threshold'")
    public double getRefreshThreshold() {
        return flagRepo.getFloat("SESSION_REFRESH_THRESHOLD", DEFAULT_REFRESH_THRESHOLD);
    }

    /**
     * Returns the grace window during which a rotated token remains valid.
     *
     * @return grace window in seconds from {@code SESSION_ROTATION_GRACE_SECONDS} flag
     */
    @Cacheable(cacheNames = "feature-flags", key = "'session-rotation-grace-seconds'")
    public int getRotationGraceSeconds() {
        return flagRepo.getInt("SESSION_ROTATION_GRACE_SECONDS", DEFAULT_ROTATION_GRACE_SECONDS);
    }

    /**
     * Revokes a token by marking it as revoked in the database.
     *
     * @param token the {@link AccessToken} to revoke
     */
    @Transactional
    public void revoke(final AccessToken token) {
        token.setStatus(TokenStatus.REVOKED);
        token.setRevokedAt(Instant.now());
        tokenRepo.save(token);
    }

    /**
     * Revokes all active tokens for a user (e.g. on password change).
     *
     * @param userId the user whose sessions must be terminated
     */
    @Transactional
    public void revokeAllForUser(final Long userId) {
        tokenRepo.revokeAllForUser(userId, TokenStatus.ACTIVE, TokenStatus.REVOKED);
        LOG.info("event=TOKENS_REVOKED_ALL userId={}", userId);
    }

    /**
     * Rotates a token: issues a fresh token with the same metadata and lets the old one
     * expire after a short grace window.
     *
     * <p>Called by {@code TokenAuthenticationFilter} when the refresh threshold is crossed.
     * A pessimistic write lock serializes concurrent rotation attempts on the same token.
     *
     * <p>Rather than revoking the old token immediately (which would 401 in-flight parallel
     * requests still carrying it), the old token is stamped {@code rotated_at} and its expiry
     * is shortened to {@code now + SESSION_ROTATION_GRACE_SECONDS}, staying {@code ACTIVE}
     * during the grace window. {@link AccessToken#needsRefresh(double)} returns {@code false}
     * once {@code rotated_at} is set, so the old token is never re-rotated.
     *
     * <p>Returns empty if the token was already rotated or revoked by a concurrent request —
     * the caller treats empty as a no-op; authentication was already established beforehand.
     *
     * @param existing the token to replace
     * @return {@link Optional} wrapping the new {@link TokenIssueResult}, or empty if already rotated
     */
    @Transactional
    public Optional<TokenIssueResult> rotate(final AccessToken existing) {
        // Pessimistic lock prevents concurrent rotation on the same token
        final Optional<AccessToken> locked = tokenRepo.findByTokenHashAndStatusForUpdate(
            existing.getTokenHash(), TokenStatus.ACTIVE);
        if (locked.isEmpty()) {
            LOG.warn("event=TOKEN_ROTATE_SKIPPED reason=already_revoked userId={}",
                existing.getUser() != null ? existing.getUser().getId() : "?");
            return Optional.empty();
        }
        final AccessToken old = locked.get();
        // A concurrent request already rotated this token while we waited on the lock.
        if (old.getRotatedAt() != null) {
            LOG.debug("event=TOKEN_ROTATE_SKIPPED reason=already_rotated userId={}",
                old.getUser() != null ? old.getUser().getId() : "?");
            return Optional.empty();
        }

        final Instant now = Instant.now();
        // Via the proxy so the @Cacheable flag value is read from the cache, not recomputed.
        final int graceSeconds = self.getRotationGraceSeconds();
        final Instant graceExpiry = now.plusSeconds(graceSeconds);
        old.setRotatedAt(now);
        // Keep ACTIVE but shorten expiry to the grace deadline (never extend it).
        if (old.getExpiresAt().isAfter(graceExpiry)) {
            old.setExpiresAt(graceExpiry);
        }
        tokenRepo.save(old);
        LOG.info("event=TOKEN_ROTATED userId={} authMethod={} graceSeconds={}",
            old.getUser() != null ? old.getUser().getId() : "?", old.getAuthMethod(),
            graceSeconds);
        return Optional.of(doIssue(
            old.getUser(),
            old.getDeviceFingerprint(),
            old.getDeviceName(),
            old.getUserAgent(),
            old.getIpAddress(),
            old.getAuthMethod(),
            old.isRememberMe(),
            false // rotation: do not enforce MAX_SESSIONS (old token still ACTIVE during grace)
        ));
    }

    /**
     * Revokes a session by its raw token value — called at logout.
     *
     * <p>Silent no-op if the token is not found or already revoked — logout must never fail.
     *
     * @param rawToken the raw opaque token from the session cookie, or {@code null}
     */
    @Transactional
    public void revokeByRawToken(final String rawToken) {
        if (rawToken == null || rawToken.isBlank()) {
            return;
        }
        tokenRepo.findByTokenHashAndStatus(CryptoUtils.sha256(rawToken), TokenStatus.ACTIVE)
            .ifPresent(t -> {
                t.setStatus(TokenStatus.REVOKED);
                t.setRevokedAt(Instant.now());
                tokenRepo.save(t);
                LOG.info("event=TOKEN_REVOKED userId={}",
                    t.getUser() != null ? t.getUser().getId() : "?");
            });
    }

    // ----------------------------------------------------------------
    // Result record
    // ----------------------------------------------------------------

    /**
     * Carries the raw token and metadata returned after issuance or rotation.
     *
     * @param rawToken       the raw opaque token — send to client, never store
     * @param expiresAt      token expiry as an {@link Instant}
     * @param ttlSeconds     TTL in seconds (for cookie {@code Max-Age})
     */
    public record TokenIssueResult(String rawToken, Instant expiresAt, int ttlSeconds) {}
}

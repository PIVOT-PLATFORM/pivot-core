package fr.pivot.agilite.testsupport.auth.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * Read-only mirror of {@code public.access_tokens} (EN53.1 Vague 1) — <strong>test-only</strong>.
 *
 * <p>Moved here from {@code src/main/java} during the modulith merge (EN53.1 Vague 1): this
 * module used to duplicate {@code pivot-core}'s own opaque-token validation algorithm in
 * production ({@code fr.pivot.agilite.auth.TokenValidationService}, EN08.3/ADR-022, the accepted
 * pattern for a truly standalone deployment). Now that this module runs inside the same JVM/
 * Spring context as {@code pivot-core-app}, that duplication is no longer appropriate in
 * production: {@code fr.pivot.auth.service.TokenService} (the shell's own opaque-token service,
 * which already implements {@code fr.pivot.core.auth.AuthenticatedPrincipalResolver}) is the
 * single {@code AuthenticatedPrincipalResolver} bean in the merged application context, and every
 * consumer in this module ({@code RequestPrincipalResolver}, {@code WheelChannelInterceptor},
 * {@code RetroSessionAccessService}) was already coded against that shared interface, never
 * against the concrete implementation — so removing the duplicate production bean rewires them
 * onto the shell's real token validation with zero code change on their part.
 *
 * <p>This class (and its sibling {@link fr.pivot.agilite.testsupport.auth.repository.AccessTokenReadRepository})
 * only still exist to let {@link fr.pivot.agilite.testsupport.auth.TestAuthenticatedPrincipalResolver}
 * back this module's own <em>isolated</em> {@code @SpringBootTest} suite ({@code
 * fr.pivot.agilite.AgiliteTestApplication}) — that isolated context has no dependency on {@code
 * pivot-core-app}'s classes at all (this module only depends on {@code pivot-core-starter}), so
 * it has no {@code AuthenticatedPrincipalResolver} bean of its own unless one is provided here.
 * Never compiled into the production library jar consumed by {@code pivot-core-app} (test
 * sources are never packaged).
 */
@Entity
@Table(schema = "public", name = "access_tokens")
public class PlatformAccessToken {

    @Id
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    /** SHA-256 hex-encoded hash of the raw bearer token — the raw token itself is never stored. */
    @Column(name = "token_hash", nullable = false, length = 64)
    private String tokenHash;

    /** Lifecycle status, lowercase in the database ({@code active}/{@code expired}/{@code revoked}). */
    @Column(name = "status", nullable = false, length = 10)
    private String status;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    /** No-argument constructor required by JPA. */
    protected PlatformAccessToken() {
    }

    /** @return database primary key */
    public Long getId() {
        return id;
    }

    /** @return the owning {@code public.users.id} */
    public Long getUserId() {
        return userId;
    }

    /** @return the SHA-256 hex-encoded hash of the raw token */
    public String getTokenHash() {
        return tokenHash;
    }

    /** @return the lowercase lifecycle status ({@code active}/{@code expired}/{@code revoked}) */
    public String getStatus() {
        return status;
    }

    /** @return the absolute expiry timestamp */
    public Instant getExpiresAt() {
        return expiresAt;
    }

    /** @return the token creation timestamp, used for tenant-invalidation comparison */
    public Instant getCreatedAt() {
        return createdAt;
    }
}

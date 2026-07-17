package fr.pivot.collaboratif.testsupport.auth.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * Read-only mirror of {@code public.users} (EN53.2 Vague 2) — <strong>test-only</strong>.
 *
 * <p>Moved here from {@code src/main/java} during the modulith merge (EN53.2 Vague 2, mirroring
 * the agilite module's own EN53.1 Vague 1 move of its sibling auth entities): this module used to
 * duplicate {@code pivot-core}'s own opaque-token validation algorithm in production ({@code
 * fr.pivot.collaboratif.auth.TokenValidationService}, EN08.3/ADR-022, the accepted pattern for a
 * truly standalone deployment — this repo's own {@code SecurityConfig} permit-all workaround was
 * required alongside it only because {@code pivot-core-starter} 0.27.1 transitively pulled in
 * {@code spring-boot-starter-security}, both removed by this same pass). Now that this module runs
 * inside the same JVM/Spring context as {@code pivot-core-app}, that duplication is no longer
 * appropriate in production: {@code fr.pivot.auth.service.TokenService} (the shell's own
 * opaque-token service, which already implements {@code
 * fr.pivot.core.auth.AuthenticatedPrincipalResolver}) is the single {@code
 * AuthenticatedPrincipalResolver} bean in the merged application context, and every consumer in
 * this module ({@code CollaboratifRequestPrincipalResolver}) was already coded against that shared
 * interface, never against the concrete implementation — so removing the duplicate production bean
 * rewires it onto the shell's real token validation with zero code change on its part.
 *
 * <p><strong>Unlike the agilite module</strong> — whose {@code PlatformUser} stayed in {@code
 * src/main/java} because {@code WheelService}/{@code TeamMembershipService} have a legitimate,
 * ongoing business need to resolve a user's display name from {@code first_name}/{@code
 * last_name} — this module has <strong>no</strong> main-scope consumer of {@code PlatformUser} at
 * all: display names in the whiteboard's presence/participant payloads ({@code ParticipantInfo},
 * {@code PresenceUserDto}) are supplied by the client itself at JOIN time, never resolved
 * server-side from {@code public.users}. This entity (and its siblings {@link PlatformAccessToken}/
 * {@link PlatformTenant}) is therefore moved to {@code src/test/java} in full, not merely trimmed
 * of its now-unused columns — there is no reason left for any copy of it to exist in this module's
 * production code.
 *
 * <p>Only still exists to let {@link
 * fr.pivot.collaboratif.testsupport.auth.TestAuthenticatedPrincipalResolver} back this module's own
 * <em>isolated</em> {@code @SpringBootTest} suite ({@code
 * fr.pivot.collaboratif.CollaboratifTestApplication}) — that isolated context has no dependency on
 * {@code pivot-core-app}'s classes at all (this module only depends on {@code
 * pivot-core-starter}), so it has no {@code AuthenticatedPrincipalResolver} bean of its own unless
 * one is provided here. Never compiled into the production library jar consumed by {@code
 * pivot-core-app} (test sources are never packaged).
 */
@Entity
@Table(schema = "public", name = "users")
public class PlatformUser {

    @Id
    private Long id;

    @Column(name = "tenant_id", nullable = false)
    private Long tenantId;

    @Column(name = "role", nullable = false, length = 50)
    private String role;

    @Column(name = "is_active", nullable = false)
    private boolean active;

    /** No-argument constructor required by JPA. */
    protected PlatformUser() {
    }

    /** @return database primary key ({@code public.users.id}) */
    public Long getId() {
        return id;
    }

    /** @return the owning {@code public.tenants.id} */
    public Long getTenantId() {
        return tenantId;
    }

    /** @return the Spring Security role, e.g. {@code ROLE_USER} */
    public String getRole() {
        return role;
    }

    /** @return {@code true} unless an admin has deactivated this account */
    public boolean isActive() {
        return active;
    }
}

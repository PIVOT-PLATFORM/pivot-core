package fr.pivot.collaboratif.whiteboard.member;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * Read-only directory view of {@code public.users} used to resolve an invitee by e-mail
 * (US08.2.5). Owned and written exclusively by {@code pivot-core}'s shell {@code fr.pivot.auth}
 * package — never persisted, updated, or deleted from this module.
 *
 * <p>Distinct from {@code fr.pivot.collaboratif.auth.entity.PlatformUser}, which deliberately
 * omits the {@code email} column because token validation never needs it. Invitation-by-email
 * does, so this narrow projection maps {@code email} in addition to {@code id}, {@code tenant_id}
 * and {@code is_active}. Resolution is always tenant-scoped: a match must belong to the inviting
 * caller's tenant, so an e-mail from another tenant is treated as unknown (404), preventing
 * cross-tenant e-mail enumeration.
 *
 * <p>Also maps {@code first_name}, {@code last_name} and {@code avatar_url} (all nullable) so the
 * share panel can display a member's identity instead of a raw numeric id. These are read the same
 * tenant-scoped way, exposed only to callers already granted board access.
 */
@Entity
@Table(schema = "public", name = "users")
public class UserDirectoryEntry {

    @Id
    private Long id;

    @Column(name = "tenant_id", nullable = false)
    private Long tenantId;

    @Column(name = "email", nullable = false)
    private String email;

    @Column(name = "is_active", nullable = false)
    private boolean active;

    @Column(name = "first_name")
    private String firstName;

    @Column(name = "last_name")
    private String lastName;

    @Column(name = "avatar_url")
    private String avatarUrl;

    /** No-argument constructor required by JPA. */
    protected UserDirectoryEntry() {
    }

    /**
     * Returns the user's identifier.
     *
     * @return the {@code public.users.id}
     */
    public Long getId() {
        return id;
    }

    /**
     * Returns the owning tenant's identifier.
     *
     * @return the {@code public.tenants.id}
     */
    public Long getTenantId() {
        return tenantId;
    }

    /**
     * Returns the user's e-mail address.
     *
     * @return the e-mail
     */
    public String getEmail() {
        return email;
    }

    /**
     * Returns whether this account is active.
     *
     * @return {@code true} unless an admin has deactivated this account
     */
    public boolean isActive() {
        return active;
    }

    /**
     * Returns the user's first name.
     *
     * @return the {@code first_name}, or {@code null} if never set (local account, or OIDC
     *         provider that omitted the {@code given_name} claim)
     */
    public String getFirstName() {
        return firstName;
    }

    /**
     * Returns the user's last name.
     *
     * @return the {@code last_name}, or {@code null} if never set
     */
    public String getLastName() {
        return lastName;
    }

    /**
     * Returns the user's avatar URL.
     *
     * @return the {@code avatar_url} (an IdP {@code picture} claim or a locally served
     *         {@code /api/avatars/...} path), or {@code null} if none
     */
    public String getAvatarUrl() {
        return avatarUrl;
    }
}

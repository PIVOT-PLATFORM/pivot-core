package fr.pivot.auth.repository;

import fr.pivot.auth.dto.UserStatus;
import fr.pivot.auth.entity.User;
import org.springframework.data.jpa.domain.Specification;

/**
 * Fabrique de {@link Specification} JPA pour les requêtes filtrées sur {@link User}.
 *
 * <p>Utilisé exclusivement par {@code AdminUserService} (US06.1.1 — {@code GET
 * /api/admin/users}). Toutes les méthodes sont null-safe côté appelant : un filtre absent
 * (paramètre {@code null}) retourne {@link Specification#unrestricted()} (spécification neutre,
 * sans prédicat) plutôt que {@code null} — {@link Specification#and(Specification)} (Spring
 * Data JPA 4.x) rejette explicitement un argument {@code null} ({@code IllegalArgumentException:
 * "Other specification must not be null"}).
 *
 * <p><strong>Isolation tenant :</strong> {@link #forTenant(Long)} est le seul point d'entrée
 * du filtrage par tenant — toujours combiné en premier par l'appelant, jamais omis.
 */
public final class UserSpecifications {

    private UserSpecifications() {
    }

    /**
     * Restreint aux utilisateurs du tenant donné.
     *
     * @param tenantId identifiant du tenant, résolu exclusivement depuis le token porteur
     * @return spécification de filtrage par tenant
     */
    public static Specification<User> forTenant(final Long tenantId) {
        return (root, query, cb) -> cb.equal(root.get("tenant").get("id"), tenantId);
    }

    /**
     * Exclut les comptes supprimés (RGPD Art. 17 — soft delete).
     *
     * @return spécification excluant {@code deleted_at IS NOT NULL}
     */
    public static Specification<User> notDeleted() {
        return (root, query, cb) -> cb.isNull(root.get("deletedAt"));
    }

    /**
     * Filtre par rôle exact (correspondance stricte, {@code role} n'étant pas une énumération
     * en base — colonne libre {@code VARCHAR(50)}).
     *
     * @param role valeur de rôle à filtrer, ou {@code null}/vide pour ignorer ce filtre
     * @return spécification de filtrage par rôle, ou {@link Specification#unrestricted()} si
     *     aucun filtre à appliquer
     */
    public static Specification<User> withRole(final String role) {
        if (role == null || role.isBlank()) {
            return Specification.unrestricted();
        }
        return (root, query, cb) -> cb.equal(root.get("role"), role);
    }

    /**
     * Filtre par statut synthétique dérivé (voir {@link UserStatus}).
     *
     * @param status statut à filtrer, ou {@code null} pour ignorer ce filtre
     * @return spécification de filtrage par statut, ou {@link Specification#unrestricted()} si
     *     aucun filtre à appliquer
     */
    public static Specification<User> withStatus(final UserStatus status) {
        if (status == null) {
            return Specification.unrestricted();
        }
        return switch (status) {
            case BLOCKED -> (root, query, cb) -> cb.isTrue(root.get("blocked"));
            case INACTIVE -> (root, query, cb) -> cb.isFalse(root.get("active"));
            case ACTIVE -> (root, query, cb) ->
                    cb.and(cb.isTrue(root.get("active")), cb.isFalse(root.get("blocked")));
        };
    }

    /**
     * Filtre plein-texte insensible à la casse sur l'e-mail, le prénom ou le nom.
     *
     * @param search terme recherché, ou {@code null}/vide pour ignorer ce filtre
     * @return spécification de recherche, ou {@link Specification#unrestricted()} si aucun
     *     filtre à appliquer
     */
    public static Specification<User> matchingSearch(final String search) {
        if (search == null || search.isBlank()) {
            return Specification.unrestricted();
        }
        final String pattern = "%" + search.trim().toLowerCase(java.util.Locale.ROOT) + "%";
        return (root, query, cb) -> cb.or(
                cb.like(cb.lower(root.get("email")), pattern),
                cb.like(cb.lower(root.get("firstName")), pattern),
                cb.like(cb.lower(root.get("lastName")), pattern));
    }
}

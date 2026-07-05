package fr.pivot.tenant.api;

import fr.pivot.tenant.entity.Tenant;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.util.StringUtils;

/**
 * Spécifications JPA composables pour le filtrage optionnel du listing super-admin des
 * tenants ({@code GET /api/superadmin/tenants} — US06.2.3).
 *
 * <p>Chaque filtre est indépendant et neutre (retourne {@code cb.conjunction()}, équivalent
 * SQL de {@code TRUE}) lorsque le paramètre correspondant est absent — {@link #filter} les
 * combine toutes avec {@code AND}, ce qui rend chaque filtre réellement optionnel.
 */
public final class TenantSpecifications {

    private TenantSpecifications() {
    }

    /**
     * Filtre sur une sous-chaîne du nom du tenant, insensible à la casse.
     *
     * @param name sous-chaîne recherchée — {@code null}/vide neutralise le filtre
     * @return spécification correspondante
     */
    public static Specification<Tenant> nameContains(final String name) {
        return (root, query, cb) -> StringUtils.hasText(name)
                ? cb.like(cb.lower(root.get("name")), "%" + name.toLowerCase(java.util.Locale.ROOT) + "%")
                : cb.conjunction();
    }

    /**
     * Filtre exact sur le statut actif/inactif du tenant.
     *
     * @param active statut recherché — {@code null} neutralise le filtre
     * @return spécification correspondante
     */
    public static Specification<Tenant> isActive(final Boolean active) {
        return (root, query, cb) -> active == null ? cb.conjunction() : cb.equal(root.get("active"), active);
    }

    /**
     * Filtre exact sur le plan souscrit.
     *
     * @param plan plan recherché — {@code null}/vide neutralise le filtre
     * @return spécification correspondante
     */
    public static Specification<Tenant> hasPlan(final String plan) {
        return (root, query, cb) -> StringUtils.hasText(plan) ? cb.equal(root.get("plan"), plan) : cb.conjunction();
    }

    /**
     * Filtre exact sur le mode d'authentification.
     *
     * @param authMode mode recherché — {@code null}/vide neutralise le filtre
     * @return spécification correspondante
     */
    public static Specification<Tenant> hasAuthMode(final String authMode) {
        return (root, query, cb) -> StringUtils.hasText(authMode) ? cb.equal(root.get("authMode"), authMode) : cb.conjunction();
    }

    /**
     * Combine les quatre filtres optionnels de l'AC US06.2.3 en une seule spécification.
     *
     * @param name     filtre optionnel sur le nom
     * @param active   filtre optionnel sur le statut actif
     * @param plan     filtre optionnel sur le plan
     * @param authMode filtre optionnel sur le mode d'authentification
     * @return spécification combinée, prête pour {@code TenantRepository.findAll(spec, pageable)}
     */
    public static Specification<Tenant> filter(final String name, final Boolean active,
            final String plan, final String authMode) {
        return Specification.allOf(
                nameContains(name),
                isActive(active),
                hasPlan(plan),
                hasAuthMode(authMode));
    }
}

package fr.pivot.auth.repository;

/**
 * Projection agrégée : nombre d'utilisateurs non supprimés par tenant.
 *
 * <p>Utilisée par {@code fr.pivot.tenant.api.SuperAdminTenantService} (US06.2.3) pour peupler
 * {@code TenantSummaryDto.userCount} sans compteur stocké sur {@code Tenant} — un décompte
 * par jointure/agrégation, recalculé à chaque requête.
 */
public interface TenantUserCountProjection {

    /**
     * @return identifiant du tenant
     */
    Long getTenantId();

    /**
     * @return nombre d'utilisateurs non supprimés rattachés à ce tenant
     */
    long getUserCount();
}

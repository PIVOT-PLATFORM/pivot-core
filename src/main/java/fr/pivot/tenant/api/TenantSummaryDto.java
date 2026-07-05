package fr.pivot.tenant.api;

import fr.pivot.tenant.entity.Tenant;
import java.time.Instant;

/**
 * DTO représentant un tenant pour l'écran de supervision plateforme (super-admin)
 * — US06.2.3 « Super admin liste tous les tenants ».
 *
 * <p>Distinct de {@link Tenant} (entité JPA) : n'expose jamais l'entité directement en API
 * (règle absolue CLAUDE.md). {@code userCount} est calculé par une requête d'agrégation dédiée
 * (voir {@link SuperAdminTenantService}) — ce n'est pas un compteur stocké sur {@link Tenant}.
 *
 * @param id        identifiant technique du tenant
 * @param slug      identifiant lisible unique du tenant
 * @param name      nom affiché du tenant
 * @param plan      plan souscrit ({@code SAAS}, {@code ENTERPRISE}, {@code TRIAL})
 * @param authMode  mode d'authentification ({@code SAAS}, {@code ENTERPRISE}, {@code HYBRID})
 * @param isActive  {@code true} si le tenant est actif
 * @param userCount nombre d'utilisateurs non supprimés rattachés au tenant
 * @param createdAt date de création du tenant
 */
public record TenantSummaryDto(
        Long id,
        String slug,
        String name,
        String plan,
        String authMode,
        boolean isActive,
        long userCount,
        Instant createdAt) {

    /**
     * Construit le DTO à partir de l'entité {@link Tenant} et d'un décompte d'utilisateurs
     * pré-calculé par lot (voir {@link SuperAdminTenantService#listTenants}).
     *
     * @param tenant    entité tenant source
     * @param userCount nombre d'utilisateurs non supprimés rattachés à ce tenant
     * @return DTO résumé du tenant
     */
    public static TenantSummaryDto from(final Tenant tenant, final long userCount) {
        return new TenantSummaryDto(
                tenant.getId(),
                tenant.getSlug(),
                tenant.getName(),
                tenant.getPlan(),
                tenant.getAuthMode(),
                tenant.isActive(),
                userCount,
                tenant.getCreatedAt());
    }
}

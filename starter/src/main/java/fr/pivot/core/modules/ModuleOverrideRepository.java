package fr.pivot.core.modules;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/**
 * Accès aux overrides SUPER_ADMIN d'activation de module par tenant
 * ({@code public.module_overrides}).
 */
public interface ModuleOverrideRepository extends JpaRepository<ModuleOverride, Long> {

    /**
     * Recherche l'override actif d'un module pour un tenant.
     *
     * @param tenantId identifiant du tenant
     * @param moduleId identifiant technique du module
     * @return l'override s'il existe, {@link Optional#empty()} sinon (équivaut à pas d'override)
     */
    Optional<ModuleOverride> findByTenantIdAndModuleId(Long tenantId, String moduleId);

    /**
     * Supprime l'override d'un couple (tenant, module), s'il existe.
     *
     * @param tenantId identifiant du tenant
     * @param moduleId identifiant technique du module
     * @return nombre de lignes supprimées (0 ou 1) — permet à l'appelant de savoir si un
     *     override existait effectivement avant l'appel
     */
    long deleteByTenantIdAndModuleId(Long tenantId, String moduleId);
}

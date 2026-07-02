package fr.pivot.core.modules;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * Accès aux états d'activation des modules par tenant ({@code public.module_activations}).
 */
public interface ModuleActivationRepository extends JpaRepository<ModuleActivation, Long> {

    /**
     * Recherche l'état d'activation d'un module pour un tenant.
     *
     * @param tenantId identifiant du tenant
     * @param moduleId identifiant technique du module
     * @return l'état s'il existe, {@link Optional#empty()} sinon (équivaut à désactivé)
     */
    Optional<ModuleActivation> findByTenantIdAndModuleId(Long tenantId, String moduleId);

    /**
     * Liste tous les états d'activation d'un tenant.
     *
     * @param tenantId identifiant du tenant
     * @return liste des états, jamais {@code null}
     */
    List<ModuleActivation> findAllByTenantId(Long tenantId);
}

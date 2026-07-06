package fr.pivot.modules.api;

import fr.pivot.core.modules.ModuleActivationService;
import fr.pivot.core.modules.ModuleOverride;
import fr.pivot.core.modules.UnknownModuleException;
import fr.pivot.tenant.api.TenantNotFoundException;
import fr.pivot.tenant.entity.Tenant;
import fr.pivot.tenant.repository.TenantRepository;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Façade d'administration plateforme au-dessus de {@link ModuleActivationService} — expose la
 * pose/le retrait d'un override d'activation de module par tenant aux seuls super
 * administrateurs (US03.3.2 « SUPER_ADMIN active/désactive un module par tenant (override) »).
 *
 * <p>Ne modifie pas {@link ModuleActivationService} au-delà de ses méthodes dédiées
 * {@code setOverride}/{@code removeOverride} (partagé, contrat inter-repos) : ce service ajoute
 * la vérification de rôle ({@code @PreAuthorize}) et la validation d'existence du tenant ciblé
 * — {@code tenantId} est un {@code @PathVariable} arbitraire (SUPER_ADMIN est cross-tenant,
 * voir CLAUDE.md), jamais garanti exister comme l'est le tenant du token porteur pour
 * {@code AdminModuleActivationService}. Sans cette validation, un {@code tenantId} inexistant
 * échouerait avec une {@code DataIntegrityViolationException} brute (violation de FK) au lieu
 * d'un {@code 404} explicite.
 *
 * <p><strong>Sécurité — RBAC porté par le service, pas seulement le contrôleur :</strong>
 * {@code @PreAuthorize("hasRole('SUPER_ADMIN')")} est évalué par le proxy Spring Method Security
 * ({@code @EnableMethodSecurity}, activé dans {@code SecurityConfig}) à chaque appel, même motif
 * que {@code AdminModuleActivationService}/{@code SuperAdminTenantService}.
 */
@Service
public class ModuleOverrideService {

    private final ModuleActivationService moduleActivationService;
    private final TenantRepository tenantRepository;

    /**
     * Construit le service avec ses collaborateurs.
     *
     * @param moduleActivationService service partagé de résolution/mutation des overrides
     * @param tenantRepository        accès BDD aux tenants (validation d'existence)
     */
    public ModuleOverrideService(final ModuleActivationService moduleActivationService,
                                  final TenantRepository tenantRepository) {
        this.moduleActivationService = moduleActivationService;
        this.tenantRepository = tenantRepository;
    }

    /**
     * Pose ou remplace un override forçant l'état d'un module pour un tenant donné.
     *
     * @param tenantId identifiant du tenant ciblé
     * @param moduleId identifiant technique du module à forcer
     * @param enabled  valeur forcée (activé/désactivé)
     * @return le résultat de la mutation (tenant, module, override actif, état effectif)
     * @throws TenantNotFoundException  si {@code tenantId} ne correspond à aucun tenant
     * @throws UnknownModuleException si {@code moduleId} n'est pas enregistré dans le registre
     */
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    @Transactional
    public ModuleOverrideResult setOverride(final Long tenantId, final String moduleId, final boolean enabled) {
        final Tenant tenant = requireTenant(tenantId);
        final ModuleOverride override = moduleActivationService.setOverride(tenantId, moduleId, enabled);
        return new ModuleOverrideResult(tenant, moduleId, true, override.isEnabled());
    }

    /**
     * Retire l'override d'un couple (tenant, module) — le module revient au comportement porté
     * par le choix de l'admin du tenant.
     *
     * @param tenantId identifiant du tenant ciblé
     * @param moduleId identifiant technique du module dont l'override doit être retiré
     * @return le résultat de la mutation (tenant, module, override retiré, état effectif après
     *     retrait) — idempotent, aucune erreur si aucun override n'existait
     * @throws TenantNotFoundException  si {@code tenantId} ne correspond à aucun tenant
     * @throws UnknownModuleException si {@code moduleId} n'est pas enregistré dans le registre
     */
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    @Transactional
    public ModuleOverrideResult removeOverride(final Long tenantId, final String moduleId) {
        final Tenant tenant = requireTenant(tenantId);
        final boolean effectiveEnabled = moduleActivationService.removeOverride(tenantId, moduleId);
        return new ModuleOverrideResult(tenant, moduleId, false, effectiveEnabled);
    }

    private Tenant requireTenant(final Long tenantId) {
        return tenantRepository.findById(tenantId).orElseThrow(() -> new TenantNotFoundException(tenantId));
    }
}

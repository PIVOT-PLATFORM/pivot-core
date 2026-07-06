package fr.pivot.core.modules;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

/**
 * Override SUPER_ADMIN de l'activation d'un module pour un tenant — table
 * {@code public.module_overrides} (US03.3.2 « SUPER_ADMIN active/désactive un module par tenant
 * (override) »).
 *
 * <p><strong>Distinct de {@link ModuleActivation}</strong> — voir la Javadoc de classe de
 * {@link ModuleActivation} et le commentaire de {@code module_overrides} dans
 * {@code V1__schema_init.sql} pour le raisonnement complet : {@link ModuleActivation} porte le
 * choix de l'administrateur <em>du tenant</em> (autorité tenant-scope, EN03.1) ;
 * {@link ModuleOverride} porte une décision plateforme du {@code SUPER_ADMIN} qui prend
 * explicitement le pas dessus (autorité cross-tenant). Ce sont deux niveaux d'autorité
 * différents — jamais la même ligne/table, sous peine qu'un admin de tenant puisse écraser
 * silencieusement une décision super-admin (ou l'inverse). Les deux entités partagent
 * uniquement leur mécanique BDD commune via {@link TenantModuleRecord} (identifiant,
 * {@code tenant_id}, {@code module_id}, horodatages) — jamais leur sémantique {@code enabled}.
 *
 * <p>Une ligne par couple (tenant, module) — contrainte unique en BDD ({@code
 * uq_mo_tenant_module}). L'absence de ligne signifie qu'aucun override n'est actif pour ce
 * couple : voir {@link ModuleActivationService#isEnabled(Long, String)} pour la résolution
 * complète (override présent → il gagne toujours ; absent → repli sur
 * {@link ModuleActivation}).
 *
 * <p>Entité interne à pivot-core : jamais exposée directement en API — projection via DTO
 * uniquement (voir {@code fr.pivot.modules.api.ModuleOverrideResponse}).
 */
@Entity
@Table(name = "module_overrides",
        uniqueConstraints = @UniqueConstraint(name = "uq_mo_tenant_module", columnNames = {"tenant_id", "module_id"}))
public class ModuleOverride extends TenantModuleRecord {

    @Column(nullable = false)
    private boolean enabled;

    /**
     * Constructeur sans argument requis par JPA.
     */
    protected ModuleOverride() {
        // JPA only
    }

    /**
     * Construit un override pour un couple (tenant, module).
     *
     * <p>Contrairement à {@link ModuleActivation}, il n'existe pas de valeur par défaut : un
     * override n'est jamais créé implicitement, uniquement via un appel explicite
     * {@code POST .../override} portant la valeur forcée voulue par le super admin.
     *
     * @param tenantId identifiant du tenant ({@code public.tenants.id})
     * @param moduleId identifiant technique du module
     * @param enabled  valeur forcée par le super admin
     */
    public ModuleOverride(final Long tenantId, final String moduleId, final boolean enabled) {
        super(tenantId, moduleId);
        this.enabled = enabled;
    }

    /**
     * Valeur forcée par le super admin.
     *
     * @return {@code true} si le super admin force l'activation, {@code false} s'il force la
     *     désactivation
     */
    public boolean isEnabled() {
        return enabled;
    }

    /**
     * Change la valeur forcée.
     *
     * @param enabled nouvelle valeur forcée
     */
    public void setEnabled(final boolean enabled) {
        this.enabled = enabled;
    }
}

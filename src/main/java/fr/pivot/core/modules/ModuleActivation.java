package fr.pivot.core.modules;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

/**
 * État d'activation d'un module PIVOT pour un tenant — table {@code public.module_activations}.
 *
 * <p>Une ligne par couple (tenant, module) — contrainte unique en BDD (migration
 * {@code V1__schema_init.sql}). L'absence de ligne équivaut à un module désactivé
 * (défaut sûr : rien n'est activé implicitement).
 *
 * <p>Colonnes/comportement communs avec {@link ModuleOverride} (identifiant, {@code tenant_id},
 * {@code module_id}, horodatages) factorisés dans {@link TenantModuleRecord} — voir sa Javadoc
 * pour le raisonnement (deux entités distinctes, pas un flag partagé).
 *
 * <p>Entité interne à pivot-core : jamais exposée directement en API — projection via DTO
 * ({@link fr.pivot.modules.registry.ModuleDto}) uniquement.
 */
@Entity
@Table(name = "module_activations",
        uniqueConstraints = @UniqueConstraint(name = "uq_ma_tenant_module", columnNames = {"tenant_id", "module_id"}))
public class ModuleActivation extends TenantModuleRecord {

    @Column(nullable = false)
    private boolean enabled;

    /**
     * Constructeur sans argument requis par JPA.
     */
    protected ModuleActivation() {
        // JPA only
    }

    /**
     * Construit un état d'activation pour un couple (tenant, module) — désactivé par défaut.
     *
     * @param tenantId identifiant du tenant ({@code public.tenants.id})
     * @param moduleId identifiant technique du module
     */
    public ModuleActivation(final Long tenantId, final String moduleId) {
        super(tenantId, moduleId);
        this.enabled = false;
    }

    /**
     * État d'activation courant.
     *
     * @return {@code true} si le module est activé pour ce tenant
     */
    public boolean isEnabled() {
        return enabled;
    }

    /**
     * Change l'état d'activation.
     *
     * @param enabled nouvel état
     */
    public void setEnabled(final boolean enabled) {
        this.enabled = enabled;
    }
}

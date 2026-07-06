package fr.pivot.core.modules;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.Instant;

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
 * silencieusement une décision super-admin (ou l'inverse).
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
public class ModuleOverride {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tenant_id", nullable = false)
    private Long tenantId;

    @Column(name = "module_id", nullable = false, length = 100)
    private String moduleId;

    @Column(nullable = false)
    private boolean enabled;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

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
        this.tenantId = tenantId;
        this.moduleId = moduleId;
        this.enabled = enabled;
    }

    /**
     * Met à jour l'horodatage de modification avant chaque UPDATE JPA.
     */
    @PreUpdate
    void onUpdate() {
        this.updatedAt = Instant.now();
    }

    /**
     * Identifiant technique de la ligne.
     *
     * @return clé primaire, {@code null} tant que non persistée
     */
    public Long getId() {
        return id;
    }

    /**
     * Tenant propriétaire de cet override.
     *
     * @return identifiant du tenant
     */
    public Long getTenantId() {
        return tenantId;
    }

    /**
     * Module concerné par cet override.
     *
     * @return identifiant technique du module
     */
    public String getModuleId() {
        return moduleId;
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

    /**
     * Horodatage de création de l'override.
     *
     * @return instant de création
     */
    public Instant getCreatedAt() {
        return createdAt;
    }

    /**
     * Horodatage de dernière modification de l'override.
     *
     * @return instant de dernière mise à jour
     */
    public Instant getUpdatedAt() {
        return updatedAt;
    }
}

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
 * État d'activation d'un module PIVOT pour un tenant — table {@code public.module_activations}.
 *
 * <p>Une ligne par couple (tenant, module) — contrainte unique en BDD (migration
 * {@code V3__module_activations.sql}). L'absence de ligne équivaut à un module désactivé
 * (défaut sûr : rien n'est activé implicitement).
 *
 * <p>Entité interne à pivot-core : jamais exposée directement en API — projection via DTO
 * ({@link fr.pivot.modules.registry.ModuleDto}) uniquement.
 */
@Entity
@Table(name = "module_activations",
        uniqueConstraints = @UniqueConstraint(name = "uq_ma_tenant_module", columnNames = {"tenant_id", "module_id"}))
public class ModuleActivation {

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
        this.tenantId = tenantId;
        this.moduleId = moduleId;
        this.enabled = false;
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
     * Tenant propriétaire de cet état d'activation.
     *
     * @return identifiant du tenant
     */
    public Long getTenantId() {
        return tenantId;
    }

    /**
     * Module concerné par cet état d'activation.
     *
     * @return identifiant technique du module
     */
    public String getModuleId() {
        return moduleId;
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

    /**
     * Horodatage de création de la ligne.
     *
     * @return instant de création
     */
    public Instant getCreatedAt() {
        return createdAt;
    }

    /**
     * Horodatage de dernière modification.
     *
     * @return instant de dernière mise à jour
     */
    public Instant getUpdatedAt() {
        return updatedAt;
    }
}

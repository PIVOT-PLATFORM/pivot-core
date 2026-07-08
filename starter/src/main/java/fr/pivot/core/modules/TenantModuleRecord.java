package fr.pivot.core.modules;

import jakarta.persistence.Column;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.MappedSuperclass;
import jakarta.persistence.PreUpdate;

import java.time.Instant;

/**
 * Base commune des deux entités « une ligne par couple (tenant, module) » du système de
 * modules : {@link ModuleActivation} (choix de l'admin du tenant, EN03.1) et
 * {@link ModuleOverride} (override SUPER_ADMIN, US03.3.2).
 *
 * <p>Factorise ce qui est strictement identique entre les deux — identifiant technique,
 * {@code tenant_id}, {@code module_id}, horodatages de création/modification et leur
 * rafraîchissement — sans présumer de leur sémantique respective : chaque sous-classe reste
 * seule responsable de son propre champ {@code enabled} (et de sa signification), de sa propre
 * table, et de son propre constructeur public. Voir la Javadoc de {@link ModuleOverride} pour la
 * distinction d'autorité entre les deux (admin de tenant vs plateforme SUPER_ADMIN) — ce n'est
 * délibérément <strong>pas</strong> la même entité avec un flag, cette classe ne fait que
 * partager la mécanique d'accès BDD commune.
 *
 * <p>{@code @MappedSuperclass} (pas d'héritage JPA {@code SINGLE_TABLE}/{@code JOINED}) : chaque
 * sous-classe reste mappée à sa propre table ({@code module_activations} /
 * {@code module_overrides}), les colonnes héritées sont simplement répliquées dans chacune —
 * aucune table ni jointure partagée, ce qui aurait mélangé les deux concepts dans un seul
 * stockage, contraire à la décision de conception ci-dessus.
 *
 * <p>Package-private : mécanique d'implémentation interne à {@code fr.pivot.core.modules}, pas
 * un type destiné à être étendu en dehors de ce package.
 */
@MappedSuperclass
abstract class TenantModuleRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tenant_id", nullable = false)
    private Long tenantId;

    @Column(name = "module_id", nullable = false, length = 100)
    private String moduleId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    /**
     * Constructeur sans argument requis par JPA.
     */
    protected TenantModuleRecord() {
        // JPA only
    }

    /**
     * Construit une ligne pour un couple (tenant, module).
     *
     * @param tenantId identifiant du tenant ({@code public.tenants.id})
     * @param moduleId identifiant technique du module
     */
    protected TenantModuleRecord(final Long tenantId, final String moduleId) {
        this.tenantId = tenantId;
        this.moduleId = moduleId;
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
     * Tenant propriétaire de cette ligne.
     *
     * @return identifiant du tenant
     */
    public Long getTenantId() {
        return tenantId;
    }

    /**
     * Module concerné par cette ligne.
     *
     * @return identifiant technique du module
     */
    public String getModuleId() {
        return moduleId;
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

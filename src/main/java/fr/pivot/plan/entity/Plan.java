package fr.pivot.plan.entity;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.HashSet;
import java.util.Set;

/**
 * Commercial/pricing plan definition — table {@code public.plans} (US03.3.1 « SUPER_ADMIN
 * définit modules disponibles par plan »).
 *
 * <p><strong>Distinct from {@code Tenant.getPlan()}</strong> (legacy deployment-scope / primary
 * auth-mode enum — {@code SAAS}/{@code ENTERPRISE}/{@code TRIAL}, see the comment on that field
 * in {@link fr.pivot.tenant.entity.Tenant}): a {@link Plan} here is a SUPER_ADMIN-managed bundle
 * of PIVOT modules a tenant subscribes to via
 * {@link fr.pivot.tenant.entity.Tenant#getBillingPlanId()}. Do not confuse the two concepts.
 *
 * <p>Module membership is a plain {@code Set<String>} of module identifiers, not a JPA
 * relationship to a {@code Module} entity: {@link fr.pivot.core.modules.ModuleRegistry} is the
 * in-code source of truth for modules (they are never persisted as rows) — same convention as
 * {@link fr.pivot.core.modules.ModuleActivation#getModuleId()}. {@code @ElementCollection} +
 * {@code @CollectionTable} is therefore the correct JPA idiom for this M-N-to-a-plain-identifier
 * association, backed by {@code public.plan_modules} (migration {@code V1__schema_init.sql}).
 *
 * <p><strong>@implNote — future enforcement:</strong> this US only covers plan *definition*
 * (which modules a plan bundles). Enforcing that a tenant admin can only activate a module
 * included in their tenant's plan (blocking {@code AdminModuleActivationService#activate} for
 * modules outside {@code Tenant#getBillingPlanId()}'s bundle) is explicitly out of scope here —
 * a distinct, future US. See {@link fr.pivot.modules.api.ModuleNotInPlanException} for the
 * currently-documented simplification this leaves in place.
 */
@Entity
@Table(name = "plans")
public class Plan {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 100)
    private String name;

    @ElementCollection
    @CollectionTable(name = "plan_modules", joinColumns = @JoinColumn(name = "plan_id"))
    @Column(name = "module_id")
    private Set<String> moduleIds = new HashSet<>();

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    /**
     * Met à jour l'horodatage de modification avant chaque UPDATE JPA.
     */
    @PreUpdate
    void onUpdate() {
        this.updatedAt = Instant.now();
    }

    /**
     * Identifiant technique du plan.
     *
     * @return clé primaire, {@code null} tant que non persisté
     */
    public Long getId() {
        return id;
    }

    /**
     * Nom du plan, unique en base ({@code uq_plans_name}).
     *
     * @return nom du plan
     */
    public String getName() {
        return name;
    }

    /**
     * Change le nom du plan.
     *
     * @param name nouveau nom, non vide
     */
    public void setName(final String name) {
        this.name = name;
    }

    /**
     * Identifiants des modules bundlés dans ce plan.
     *
     * @return ensemble mutable des identifiants de module ({@link fr.pivot.core.modules.ModuleRegistry})
     */
    public Set<String> getModuleIds() {
        return moduleIds;
    }

    /**
     * Remplace intégralement l'ensemble des modules bundlés dans ce plan.
     *
     * @param moduleIds nouvel ensemble d'identifiants de module — un ensemble vide est une
     *                  valeur valide (retire tous les modules du plan)
     */
    public void setModuleIds(final Set<String> moduleIds) {
        this.moduleIds = moduleIds;
    }

    /**
     * Horodatage de création du plan.
     *
     * @return instant de création
     */
    public Instant getCreatedAt() {
        return createdAt;
    }

    /**
     * Horodatage de dernière modification du plan.
     *
     * @return instant de dernière mise à jour
     */
    public Instant getUpdatedAt() {
        return updatedAt;
    }
}

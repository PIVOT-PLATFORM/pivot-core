package fr.pivot.core.team;

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
 * Équipe PIVOT — table {@code public.teams} (schéma partagé, propriété {@code pivot-core}).
 *
 * <p>Entité fondatrice EN17.1 (volet team, {@code pivot-core#171}) : chaque
 * {@code pivot-xxx-core} référence {@code public.teams(id)} par FK cross-schéma (convention déjà
 * documentée par EN17.4) pour rattacher ses propres entités métier (ex. l'entité
 * {@code pilotage.applications} à venir, voir {@code en-schema-flyway-pilotage.md}) à une équipe
 * PIVOT — jamais de duplication locale de la notion d'équipe dans un schéma module.
 *
 * <p>Exportée par {@code fr.pivot:pivot-core-starter} — consommée par tous les repos modules.
 *
 * <p><strong>Scope volontairement limité</strong> : ce ticket ne livre que l'entité, la migration
 * ({@code V1__schema_init.sql}) et les repositories ({@link TeamRepository},
 * {@link TeamMemberRepository}) — pas d'API REST ni de service applicatif, aucune US ne
 * spécifiant encore la gestion applicative des équipes (création, invitation de membres...).
 * Ce n'est pas une extraction (aucun code équivalent n'existait avant), mais la création d'une
 * feature minimale nécessaire pour débloquer la convention FK cross-schéma déjà utilisée par les
 * repos {@code pivot-xxx-core}.
 *
 * <p><strong>Hiérarchie d'équipes (auto-référence anticipée) :</strong> {@code parent_team_id}
 * (nullable) permet à une équipe d'être soit orpheline (racine, {@code null}) soit rattachée à
 * une équipe parente — structure en arbre. Ajouté par anticipation pour {@code E15}/{@code
 * EN15.3} ({@code pivot-docs} EPIC-equipes, PR pivot-docs#151), encore {@code phase-3}/verrouillé
 * — seule la colonne existe ici, aucune logique de parcours d'arbre ou de partage par équipe
 * n'est implémentée dans ce ticket (évite une migration de retrofit une fois E15 déverrouillé).
 */
@Entity
@Table(name = "teams",
        uniqueConstraints = @UniqueConstraint(name = "uq_teams_tenant_name", columnNames = {"tenant_id", "name"}))
public class Team {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tenant_id", nullable = false)
    private Long tenantId;

    @Column(nullable = false)
    private String name;

    /**
     * Identifiant de l'équipe parente ({@code public.teams.id}), ou {@code null} si cette équipe
     * est orpheline (niveau racine). Voir la Javadoc de classe pour le raisonnement (E15/EN15.3).
     */
    @Column(name = "parent_team_id")
    private Long parentTeamId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    /**
     * Constructeur sans argument requis par JPA.
     */
    protected Team() {
        // JPA only
    }

    /**
     * Construit une équipe pour un tenant.
     *
     * @param tenantId identifiant du tenant propriétaire ({@code public.tenants.id})
     * @param name     nom de l'équipe, unique au sein du tenant ({@code uq_teams_tenant_name})
     */
    public Team(final Long tenantId, final String name) {
        this.tenantId = tenantId;
        this.name = name;
    }

    /**
     * Met à jour l'horodatage de modification avant chaque UPDATE JPA.
     */
    @PreUpdate
    void onUpdate() {
        this.updatedAt = Instant.now();
    }

    /**
     * Identifiant technique de l'équipe.
     *
     * @return clé primaire, {@code null} tant que non persistée
     */
    public Long getId() {
        return id;
    }

    /**
     * Tenant propriétaire de cette équipe.
     *
     * @return identifiant du tenant ({@code public.tenants.id})
     */
    public Long getTenantId() {
        return tenantId;
    }

    /**
     * Nom de l'équipe.
     *
     * @return nom, unique au sein du tenant
     */
    public String getName() {
        return name;
    }

    /**
     * Renomme l'équipe.
     *
     * @param name nouveau nom
     */
    public void setName(final String name) {
        this.name = name;
    }

    /**
     * Équipe parente dans la hiérarchie (E15/EN15.3).
     *
     * @return identifiant de l'équipe parente, ou {@code null} si cette équipe est orpheline
     *     (niveau racine)
     */
    public Long getParentTeamId() {
        return parentTeamId;
    }

    /**
     * Rattache (ou détache, avec {@code null}) cette équipe à une équipe parente.
     *
     * @param parentTeamId identifiant de l'équipe parente, ou {@code null} pour en faire une
     *                      équipe orpheline (racine)
     */
    public void setParentTeamId(final Long parentTeamId) {
        this.parentTeamId = parentTeamId;
    }

    /**
     * Horodatage de création.
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

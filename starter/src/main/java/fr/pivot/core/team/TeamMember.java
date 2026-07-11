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
 * Appartenance d'un utilisateur à une {@link Team} — table {@code public.team_members}.
 *
 * <p><strong>Rôle intra-équipe (anticipé, ADR-027) :</strong> la colonne {@code role}
 * ({@link #ROLE_RESPONSABLE} | {@link #ROLE_ADJOINT} | {@link #ROLE_MEMBRE}, défaut
 * {@link #ROLE_MEMBRE}, bornée par un {@code CHECK} côté BDD) et {@code updated_at} sont un
 * sous-ensemble découplé et inerte du concept d'équipes raffiné par l'ADR-027
 * ({@code pivot-docs#227}, {@code E15} encore {@code phase-3}/verrouillé), replié ici sous la même
 * logique que {@code parent_team_id} (EN17.1, {@code pivot-core#171}). L'appartenance porte
 * désormais un rôle <em>modifiable</em> — d'où {@code updated_at} et le {@link #onUpdate()}
 * {@code @PreUpdate} : elle n'est donc plus une association N-N pure « on adhère ou pas », contrairement
 * à ce que documentait la version EN17.1 de cette classe. Aucune logique métier de rôle (permission,
 * promotion, invariant « au moins un RESPONSABLE »...) n'est implémentée dans ce ticket — seul le
 * socle entité + colonnes existe.
 *
 * <p>Exportée par {@code fr.pivot:pivot-core-starter}.
 */
@Entity
@Table(name = "team_members",
        uniqueConstraints = @UniqueConstraint(name = "uq_team_members_team_user", columnNames = {"team_id", "user_id"}))
public class TeamMember {

    /** Rôle : responsable de l'équipe (ADR-027). */
    public static final String ROLE_RESPONSABLE = "RESPONSABLE";

    /** Rôle : adjoint au responsable (ADR-027). */
    public static final String ROLE_ADJOINT = "ADJOINT";

    /** Rôle : membre simple, valeur par défaut (ADR-027). */
    public static final String ROLE_MEMBRE = "MEMBRE";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "team_id", nullable = false)
    private Long teamId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    /**
     * Rôle de l'utilisateur au sein de l'équipe (ADR-027) : {@link #ROLE_RESPONSABLE},
     * {@link #ROLE_ADJOINT} ou {@link #ROLE_MEMBRE} (défaut). Colonne anticipée, bornée par un
     * {@code CHECK} côté BDD ; aucune logique de permission n'est implémentée dans ce ticket.
     */
    @Column(nullable = false, length = 20)
    private String role = ROLE_MEMBRE;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    /**
     * Constructeur sans argument requis par JPA.
     */
    protected TeamMember() {
        // JPA only
    }

    /**
     * Rattache un utilisateur à une équipe.
     *
     * @param teamId identifiant de l'équipe ({@code public.teams.id})
     * @param userId identifiant de l'utilisateur ({@code public.users.id})
     */
    public TeamMember(final Long teamId, final Long userId) {
        this.teamId = teamId;
        this.userId = userId;
    }

    /**
     * Identifiant technique de la ligne d'appartenance.
     *
     * @return clé primaire, {@code null} tant que non persistée
     */
    public Long getId() {
        return id;
    }

    /**
     * Équipe concernée.
     *
     * @return identifiant de l'équipe ({@code public.teams.id})
     */
    public Long getTeamId() {
        return teamId;
    }

    /**
     * Utilisateur membre de l'équipe.
     *
     * @return identifiant de l'utilisateur ({@code public.users.id})
     */
    public Long getUserId() {
        return userId;
    }

    /**
     * Rôle de l'utilisateur au sein de l'équipe (ADR-027).
     *
     * @return rôle courant ({@link #ROLE_RESPONSABLE}, {@link #ROLE_ADJOINT} ou
     *     {@link #ROLE_MEMBRE})
     */
    public String getRole() {
        return role;
    }

    /**
     * Définit le rôle de l'utilisateur au sein de l'équipe (ADR-027).
     *
     * @param role l'un de {@link #ROLE_RESPONSABLE}, {@link #ROLE_ADJOINT}, {@link #ROLE_MEMBRE}
     *     (borné par le {@code CHECK} côté BDD)
     */
    public void setRole(final String role) {
        this.role = role;
    }

    /**
     * Horodatage de création de l'appartenance.
     *
     * @return instant de création
     */
    public Instant getCreatedAt() {
        return createdAt;
    }

    /**
     * Horodatage de dernière modification de l'appartenance (ADR-027).
     *
     * @return instant de dernière mise à jour
     */
    public Instant getUpdatedAt() {
        return updatedAt;
    }

    /**
     * Met à jour l'horodatage de modification avant chaque UPDATE JPA.
     */
    @PreUpdate
    void onUpdate() {
        this.updatedAt = Instant.now();
    }
}

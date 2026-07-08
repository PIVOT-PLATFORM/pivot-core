package fr.pivot.core.team;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.Instant;

/**
 * Appartenance d'un utilisateur à une {@link Team} — table {@code public.team_members}
 * (association N-N pure).
 *
 * <p>Pas de colonne {@code role} : aucune US ne spécifie encore de hiérarchie ou de permission au
 * sein d'une équipe (voir la Javadoc de {@link Team} pour le scope de ce ticket, EN17.1 volet
 * team, {@code pivot-core#171}) — un utilisateur appartient ou non à l'équipe, sans plus de
 * sémantique pour l'instant. Suit la même convention que {@code plan_modules} dans
 * {@code V1__schema_init.sql} : table d'association pure, pas de {@code updated_at}
 * (l'appartenance ne se « modifie » pas, elle se crée ou se supprime).
 *
 * <p>Exportée par {@code fr.pivot:pivot-core-starter}.
 */
@Entity
@Table(name = "team_members",
        uniqueConstraints = @UniqueConstraint(name = "uq_team_members_team_user", columnNames = {"team_id", "user_id"}))
public class TeamMember {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "team_id", nullable = false)
    private Long teamId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

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
     * Horodatage de création de l'appartenance.
     *
     * @return instant de création
     */
    public Instant getCreatedAt() {
        return createdAt;
    }
}

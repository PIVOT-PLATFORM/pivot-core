package fr.pivot.core.team;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * Accès aux appartenances équipe/utilisateur ({@code public.team_members}).
 */
public interface TeamMemberRepository extends JpaRepository<TeamMember, Long> {

    /**
     * Liste les membres d'une équipe.
     *
     * @param teamId identifiant de l'équipe
     * @return liste des appartenances, jamais {@code null}
     */
    List<TeamMember> findAllByTeamId(Long teamId);

    /**
     * Liste les équipes auxquelles appartient un utilisateur.
     *
     * @param userId identifiant de l'utilisateur
     * @return liste des appartenances, jamais {@code null}
     */
    List<TeamMember> findAllByUserId(Long userId);

    /**
     * Recherche l'appartenance d'un utilisateur à une équipe donnée
     * ({@code uq_team_members_team_user}).
     *
     * @param teamId identifiant de l'équipe
     * @param userId identifiant de l'utilisateur
     * @return l'appartenance si elle existe, {@link Optional#empty()} sinon
     */
    Optional<TeamMember> findByTeamIdAndUserId(Long teamId, Long userId);
}

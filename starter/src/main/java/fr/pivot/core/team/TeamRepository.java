package fr.pivot.core.team;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * Accès aux équipes PIVOT ({@code public.teams}).
 */
public interface TeamRepository extends JpaRepository<Team, Long> {

    /**
     * Liste toutes les équipes d'un tenant.
     *
     * @param tenantId identifiant du tenant
     * @return liste des équipes, jamais {@code null}
     */
    List<Team> findAllByTenantId(Long tenantId);

    /**
     * Recherche une équipe par tenant et nom ({@code uq_teams_tenant_name}).
     *
     * @param tenantId identifiant du tenant
     * @param name     nom de l'équipe
     * @return l'équipe si elle existe, {@link Optional#empty()} sinon
     */
    Optional<Team> findByTenantIdAndName(Long tenantId, String name);
}

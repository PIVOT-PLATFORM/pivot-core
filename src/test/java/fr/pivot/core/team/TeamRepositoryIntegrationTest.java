package fr.pivot.core.team;

import fr.pivot.AbstractIntegrationTest;
import fr.pivot.auth.entity.User;
import fr.pivot.auth.repository.UserRepository;
import fr.pivot.tenant.entity.Tenant;
import fr.pivot.tenant.repository.TenantRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests d'intégration Testcontainers (PostgreSQL) pour {@link Team}/{@link TeamMember} —
 * EN17.1 (volet team, pivot-core#171).
 *
 * <p>Traçabilité :
 * <ul>
 *   <li>migration {@code V1__schema_init.sql} : tables {@code teams}/{@code team_members},
 *       contraintes uniques ({@code tenant_id, name}) / ({@code team_id, user_id}), FK vers
 *       {@code public.tenants}/{@code public.users} ;</li>
 *   <li>repositories {@link TeamRepository}/{@link TeamMemberRepository} : requêtes dérivées ;</li>
 *   <li>{@code ON DELETE CASCADE} : suppression d'une équipe supprime ses appartenances.</li>
 * </ul>
 */
class TeamRepositoryIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private TeamRepository teamRepository;

    @Autowired
    private TeamMemberRepository teamMemberRepository;

    @Autowired
    private TenantRepository tenantRepository;

    @Autowired
    private UserRepository userRepository;

    private Long tenantId;
    private Long userId;

    @BeforeEach
    void setUp() {
        tenantId = tenantRepository.findBySlug("pivot-saas").orElseThrow().getId();

        final Tenant tenant = tenantRepository.findById(tenantId).orElseThrow();
        final User user = new User();
        user.setTenant(tenant);
        user.setEmail("team-member-" + System.nanoTime() + "@pivot.test");
        userId = userRepository.save(user).getId();
    }

    @AfterEach
    void tearDown() {
        teamMemberRepository.deleteAll();
        teamRepository.deleteAll();
        userRepository.deleteById(userId);
    }

    // ----------------------------------------------------------------
    // teams
    // ----------------------------------------------------------------

    @Test
    void save_shouldPersistTeam_andBeFoundByTenantAndName() {
        teamRepository.save(new Team(tenantId, "Squad Alpha"));

        final Optional<Team> found = teamRepository.findByTenantIdAndName(tenantId, "Squad Alpha");

        assertThat(found).isPresent();
        assertThat(found.get().getId()).isNotNull();
        assertThat(found.get().getTenantId()).isEqualTo(tenantId);
    }

    @Test
    void findAllByTenantId_shouldReturnOnlyTeamsOfThatTenant() {
        teamRepository.save(new Team(tenantId, "Squad Alpha"));
        teamRepository.save(new Team(tenantId, "Squad Bravo"));

        final List<Team> teams = teamRepository.findAllByTenantId(tenantId);

        assertThat(teams).hasSize(2)
                .extracting(Team::getName)
                .containsExactlyInAnyOrder("Squad Alpha", "Squad Bravo");
    }

    @Test
    void save_shouldRejectDuplicateNameWithinSameTenant() {
        teamRepository.saveAndFlush(new Team(tenantId, "Squad Alpha"));

        assertThatThrownBy(() -> teamRepository.saveAndFlush(new Team(tenantId, "Squad Alpha")))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    // ----------------------------------------------------------------
    // team_members
    // ----------------------------------------------------------------

    @Test
    void save_shouldPersistMembership_andBeRetrievableBothWays() {
        final Team team = teamRepository.save(new Team(tenantId, "Squad Alpha"));

        teamMemberRepository.save(new TeamMember(team.getId(), userId));

        assertThat(teamMemberRepository.findAllByTeamId(team.getId()))
                .extracting(TeamMember::getUserId)
                .containsExactly(userId);
        assertThat(teamMemberRepository.findAllByUserId(userId))
                .extracting(TeamMember::getTeamId)
                .containsExactly(team.getId());
        assertThat(teamMemberRepository.findByTeamIdAndUserId(team.getId(), userId)).isPresent();
    }

    @Test
    void save_shouldRejectDuplicateMembership() {
        final Team team = teamRepository.save(new Team(tenantId, "Squad Alpha"));
        teamMemberRepository.saveAndFlush(new TeamMember(team.getId(), userId));

        assertThatThrownBy(() -> teamMemberRepository.saveAndFlush(new TeamMember(team.getId(), userId)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void deletingTeam_shouldCascadeDeleteMemberships() {
        final Team team = teamRepository.save(new Team(tenantId, "Squad Alpha"));
        teamMemberRepository.saveAndFlush(new TeamMember(team.getId(), userId));

        teamRepository.deleteById(team.getId());
        teamRepository.flush();

        assertThat(teamMemberRepository.findAllByTeamId(team.getId())).isEmpty();
    }

    // ----------------------------------------------------------------
    // hierarchy — parent_team_id (E15/EN15.3, pivot-docs#151, anticipation de schéma)
    // ----------------------------------------------------------------

    @Test
    void save_shouldDefaultToOrphan_whenParentTeamIdNotSet() {
        final Team saved = teamRepository.saveAndFlush(new Team(tenantId, "Squad Root"));

        assertThat(teamRepository.findById(saved.getId()).orElseThrow().getParentTeamId()).isNull();
    }

    @Test
    void save_shouldPersistParentChildRelationship() {
        final Team parent = teamRepository.saveAndFlush(new Team(tenantId, "Squad Parent"));
        final Team child = new Team(tenantId, "Squad Child");
        child.setParentTeamId(parent.getId());

        final Team savedChild = teamRepository.saveAndFlush(child);

        assertThat(teamRepository.findById(savedChild.getId()).orElseThrow().getParentTeamId())
                .isEqualTo(parent.getId());
    }

    @Test
    void deletingParentTeam_shouldSetChildParentTeamIdToNull_notCascadeDelete() {
        final Team parent = teamRepository.saveAndFlush(new Team(tenantId, "Squad Parent"));
        final Team child = new Team(tenantId, "Squad Child");
        child.setParentTeamId(parent.getId());
        final Team savedChild = teamRepository.saveAndFlush(child);

        teamRepository.deleteById(parent.getId());
        teamRepository.flush();

        final Team reloadedChild = teamRepository.findById(savedChild.getId()).orElseThrow();
        assertThat(reloadedChild.getParentTeamId()).isNull();
    }
}

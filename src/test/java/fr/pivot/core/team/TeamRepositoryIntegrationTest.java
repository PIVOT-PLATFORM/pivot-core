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
 *   <li>{@code ON DELETE CASCADE} : suppression d'une équipe supprime ses appartenances ;</li>
 *   <li>colonnes anticipées ADR-027 (pivot-docs#227) : {@code teams.slug} (unique par tenant),
 *       {@code teams.color}/{@code description}, {@code team_members.role} (défaut {@code MEMBRE},
 *       {@code CHECK}) et {@code updated_at}.</li>
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
    void save_shouldPersistSlugColorAndDescription() {
        final Team team = new Team(tenantId, "Squad Alpha");
        team.setColor("#1E90FF");
        team.setDescription("Équipe transverse produit");

        final Team saved = teamRepository.saveAndFlush(team);

        final Team reloaded = teamRepository.findById(saved.getId()).orElseThrow();
        assertThat(reloaded.getSlug()).isEqualTo("squad-alpha");
        assertThat(reloaded.getColor()).isEqualTo("#1E90FF");
        assertThat(reloaded.getDescription()).isEqualTo("Équipe transverse produit");
    }

    @Test
    void save_shouldRejectDuplicateSlugWithinSameTenant() {
        final Team first = new Team(tenantId, "Squad Alpha");
        teamRepository.saveAndFlush(first);

        final Team second = new Team(tenantId, "Squad Bravo");
        second.setSlug("squad-alpha");

        assertThatThrownBy(() -> teamRepository.saveAndFlush(second))
                .isInstanceOf(DataIntegrityViolationException.class);
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
    void save_shouldDefaultMemberRoleToMembre_andPersistExplicitRole() {
        final Team team = teamRepository.save(new Team(tenantId, "Squad Alpha"));

        final TeamMember defaulted = teamMemberRepository.saveAndFlush(
                new TeamMember(team.getId(), userId));
        assertThat(teamMemberRepository.findById(defaulted.getId()).orElseThrow().getRole())
                .isEqualTo(TeamMember.ROLE_MEMBRE);

        defaulted.setRole(TeamMember.ROLE_RESPONSABLE);
        teamMemberRepository.saveAndFlush(defaulted);
        assertThat(teamMemberRepository.findById(defaulted.getId()).orElseThrow().getRole())
                .isEqualTo(TeamMember.ROLE_RESPONSABLE);
    }

    @Test
    void save_shouldRejectMemberRoleOutsideCheckConstraint() {
        final Team team = teamRepository.save(new Team(tenantId, "Squad Alpha"));
        final TeamMember member = new TeamMember(team.getId(), userId);
        member.setRole("INTRUS");

        assertThatThrownBy(() -> teamMemberRepository.saveAndFlush(member))
                .isInstanceOf(DataIntegrityViolationException.class);
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

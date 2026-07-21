package fr.pivot.agilite.capacity;

import fr.pivot.agilite.AbstractAgiliteIntegrationTest;
import fr.pivot.agilite.testsupport.PlatformAuthTestSupport;
import fr.pivot.agilite.testsupport.PlatformAuthTestSupport.AuthFixture;
import fr.pivot.core.team.TeamMember;
import fr.pivot.core.team.TeamMemberRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration tests for {@link CapacityVelocityController} exercising the full Spring context
 * against a real PostgreSQL database (and Redis) provided by Testcontainers (F11.4).
 *
 * <p>Covers: upserting a sprint's velocity snapshot (create then update), the rolling velocity
 * history/forecast, the real+ideal burndown lines, negative-points rejection, the
 * VIEWER-forbidden-write rule, and the mandatory cross-tenant 404.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class CapacityVelocityControllerIT extends AbstractAgiliteIntegrationTest {

    private static final String CAPACITY_PATH = "/agilite/capacity/events";
    private static final Integer[] WORKING_DAYS = {1, 2, 3, 4, 5};

    @Autowired
    private WebApplicationContext wac;

    @Autowired
    private CapacityEventRepository eventRepository;

    @Autowired
    private CapacityVelocityRepository velocityRepository;

    @Autowired
    private CapacityBurndownPointRepository burndownPointRepository;

    @Autowired
    private TeamMemberRepository teamMemberRepository;

    private MockMvc mockMvc;

    private long tenantId;
    private long teamId;
    private long ownerUserId;
    private String ownerToken;

    private String otherTenantToken;

    /**
     * Sets up MockMvc and seeds a tenant with a team and a member of that team (promoted to
     * OWNER — {@link TeamMember#ROLE_RESPONSABLE}, since {@link PlatformAuthTestSupport}'s seed
     * helper defaults every membership to {@link TeamMember#ROLE_MEMBRE}/VIEWER), plus a user
     * belonging to an entirely separate tenant, before each test.
     */
    @BeforeEach
    void setUp() throws Exception {
        mockMvc = MockMvcBuilders.webAppContextSetup(wac).build();

        AuthFixture owner = PlatformAuthTestSupport.seedActiveUserWithTeamAndToken(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        tenantId = owner.tenantId();
        teamId = owner.teamId();
        ownerUserId = owner.userId();
        ownerToken = owner.rawToken();
        promoteToRole(teamId, ownerUserId, TeamMember.ROLE_RESPONSABLE);

        AuthFixture otherTenant = PlatformAuthTestSupport.seedActiveUserWithToken(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        otherTenantToken = otherTenant.rawToken();
    }

    // -------------------------------------------------------------------------
    // PATCH /capacity/events/{id}/velocity
    // -------------------------------------------------------------------------

    /**
     * Given an OWNER of the sprint's team, when PATCH .../velocity is called, then a new
     * velocity snapshot is created with the given points.
     */
    @Test
    void upsertVelocity_asOwner_createsSnapshot() throws Exception {
        UUID sprintId = seedSprint("Sprint 1", LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 14), 20.0);

        mockMvc.perform(
                        patch(CAPACITY_PATH + "/" + sprintId + "/velocity")
                                .header("Authorization", "Bearer " + ownerToken)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                                        {"pointsEngages":20,"pointsLivres":18}
                                        """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sprintEventId").value(sprintId.toString()))
                .andExpect(jsonPath("$.pointsEngages").value(20.0))
                .andExpect(jsonPath("$.pointsLivres").value(18.0))
                .andExpect(jsonPath("$.createdAt").isString());
    }

    /**
     * Given a sprint that already has a velocity snapshot, when PATCH .../velocity is called
     * again with different points, then the snapshot is replaced with the new values.
     */
    @Test
    void upsertVelocity_calledTwice_replacesSnapshot() throws Exception {
        UUID sprintId = seedSprint("Sprint 2", LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 14), 20.0);

        mockMvc.perform(
                        patch(CAPACITY_PATH + "/" + sprintId + "/velocity")
                                .header("Authorization", "Bearer " + ownerToken)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                                        {"pointsEngages":20,"pointsLivres":15}
                                        """))
                .andExpect(status().isOk());

        mockMvc.perform(
                        patch(CAPACITY_PATH + "/" + sprintId + "/velocity")
                                .header("Authorization", "Bearer " + ownerToken)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                                        {"pointsEngages":22,"pointsLivres":21}
                                        """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pointsEngages").value(22.0))
                .andExpect(jsonPath("$.pointsLivres").value(21.0));

        assertThat(velocityRepository.findBySprintEventId(sprintId)).hasValueSatisfying(v -> {
            assertThat(v.getPointsEngages()).isEqualTo(22.0);
            assertThat(v.getPointsLivres()).isEqualTo(21.0);
        });
    }

    /**
     * Given negative points, when PATCH .../velocity is called, then it returns 400 with code
     * {@code INVALID_POINTS}.
     */
    @Test
    void upsertVelocity_negativePoints_returns400() throws Exception {
        UUID sprintId = seedSprint("Sprint 3", LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 14), 20.0);

        mockMvc.perform(
                        patch(CAPACITY_PATH + "/" + sprintId + "/velocity")
                                .header("Authorization", "Bearer " + ownerToken)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                                        {"pointsEngages":-1,"pointsLivres":5}
                                        """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_POINTS"));
    }

    /**
     * Given a caller whose team role is VIEWER (default {@link TeamMember#ROLE_MEMBRE}), when
     * PATCH .../velocity is called, then it returns 403.
     */
    @Test
    void upsertVelocity_asViewer_returns403() throws Exception {
        UUID sprintId = seedSprint("Sprint 4", LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 14), 20.0);

        long viewerUserId = PlatformAuthTestSupport.seedUser(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword(), tenantId, true);
        String viewerToken = PlatformAuthTestSupport.issueToken(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword(),
                viewerUserId, "active", Instant.now().plusSeconds(3600));
        PlatformAuthTestSupport.seedTeamMember(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword(), teamId, viewerUserId);

        mockMvc.perform(
                        patch(CAPACITY_PATH + "/" + sprintId + "/velocity")
                                .header("Authorization", "Bearer " + viewerToken)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                                        {"pointsEngages":20,"pointsLivres":18}
                                        """))
                .andExpect(status().isForbidden());
    }

    /**
     * Given a sprint id belonging to a different tenant than the caller, when PATCH
     * .../velocity is called, then it returns 404 (never confirms cross-tenant existence).
     */
    @Test
    void upsertVelocity_crossTenant_returns404() throws Exception {
        UUID sprintId = seedSprint("Sprint 5", LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 14), 20.0);

        mockMvc.perform(
                        patch(CAPACITY_PATH + "/" + sprintId + "/velocity")
                                .header("Authorization", "Bearer " + otherTenantToken)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                                        {"pointsEngages":20,"pointsLivres":18}
                                        """))
                .andExpect(status().isNotFound());
    }

    // -------------------------------------------------------------------------
    // GET /capacity/events/{id}/history
    // -------------------------------------------------------------------------

    /**
     * Given a team with several closed-out sprints, when GET .../history is called, then it
     * returns the sprints oldest-first with their velocity snapshots and a rolling forecast
     * whose sample size matches the number of closed-out sprints in the window.
     */
    @Test
    void history_withSeveralSprints_returnsHistoryAndForecast() throws Exception {
        UUID sprint1 = seedSprint("Sprint 1", LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 14), 20.0);
        UUID sprint2 = seedSprint("Sprint 2", LocalDate.of(2026, 1, 15), LocalDate.of(2026, 1, 28), 20.0);
        UUID sprint3 = seedSprint("Sprint 3", LocalDate.of(2026, 1, 29), LocalDate.of(2026, 2, 11), 20.0);
        seedVelocity(sprint1, 20.0, 16.0);
        seedVelocity(sprint2, 20.0, 18.0);
        seedVelocity(sprint3, 20.0, 20.0);

        mockMvc.perform(
                        get(CAPACITY_PATH + "/" + sprint3 + "/history")
                                .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.history.length()").value(3))
                .andExpect(jsonPath("$.history[0].sprintEventId").value(sprint1.toString()))
                .andExpect(jsonPath("$.history[0].pointsLivres").value(16.0))
                .andExpect(jsonPath("$.history[2].sprintEventId").value(sprint3.toString()))
                .andExpect(jsonPath("$.forecast.sampleSize").value(3))
                .andExpect(jsonPath("$.forecast.mean").value(18.0))
                .andExpect(jsonPath("$.forecast.lowerBound").isNumber())
                .andExpect(jsonPath("$.forecast.upperBound").isNumber());
    }

    /**
     * Given a sprint with no team velocity history at all, when GET .../history is called, then
     * it returns an empty forecast (no non-empty sprint in the window).
     */
    @Test
    void history_noVelocityHistory_returnsNullForecast() throws Exception {
        UUID sprintId = seedSprint("Sprint Only", LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 14), 20.0);

        mockMvc.perform(
                        get(CAPACITY_PATH + "/" + sprintId + "/history")
                                .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.history.length()").value(1))
                .andExpect(jsonPath("$.history[0].pointsLivres").doesNotExist())
                .andExpect(jsonPath("$.forecast").doesNotExist());
    }

    // -------------------------------------------------------------------------
    // GET /capacity/events/{id}/burndown
    // -------------------------------------------------------------------------

    /**
     * Given a sprint with recorded burndown points, when GET .../burndown is called, then it
     * returns the real recorded line plus a derived ideal line from committedPoints to 0.
     */
    @Test
    void burndown_returnsRealAndIdealLines() throws Exception {
        UUID sprintId = seedSprint("Sprint Burndown", LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 3), 30.0);
        burndownPointRepository.save(new CapacityBurndownPoint(
                sprintId, LocalDate.of(2026, 1, 1), 30.0, Instant.now()));
        burndownPointRepository.save(new CapacityBurndownPoint(
                sprintId, LocalDate.of(2026, 1, 2), 20.0, Instant.now()));

        mockMvc.perform(
                        get(CAPACITY_PATH + "/" + sprintId + "/burndown")
                                .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.real.length()").value(2))
                .andExpect(jsonPath("$.real[0].date").value("2026-01-01"))
                .andExpect(jsonPath("$.real[0].pointsRestants").value(30.0))
                .andExpect(jsonPath("$.ideal.length()").value(3))
                .andExpect(jsonPath("$.ideal[0].date").value("2026-01-01"))
                .andExpect(jsonPath("$.ideal[0].pointsRestants").value(30.0))
                .andExpect(jsonPath("$.ideal[1].pointsRestants").value(15.0))
                .andExpect(jsonPath("$.ideal[2].pointsRestants").value(0.0));
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /** Seeds a SPRINT-type capacity event directly via the repository. */
    private UUID seedSprint(
            final String name, final LocalDate startDate, final LocalDate endDate, final double committedPoints) {
        CapacityEvent event = new CapacityEvent(
                tenantId, teamId, CapacityEventType.SPRINT, name, startDate, endDate, WORKING_DAYS);
        event.setCommittedPoints(committedPoints);
        return eventRepository.save(event).getId();
    }

    /** Seeds a velocity snapshot directly via the repository. */
    private void seedVelocity(final UUID sprintEventId, final double pointsEngages, final double pointsLivres) {
        velocityRepository.save(new CapacityVelocity(sprintEventId, pointsEngages, pointsLivres, Instant.now()));
    }

    /** Promotes an existing team membership to the given ADR-027 role. */
    private void promoteToRole(final long promotedTeamId, final long promotedUserId, final String role) {
        TeamMember member = teamMemberRepository.findByTeamIdAndUserId(promotedTeamId, promotedUserId).orElseThrow();
        member.setRole(role);
        teamMemberRepository.save(member);
    }
}

package fr.pivot.agilite.capacity.kpi;

import fr.pivot.agilite.AbstractAgiliteIntegrationTest;
import fr.pivot.agilite.capacity.CapacityAbsence;
import fr.pivot.agilite.capacity.CapacityAbsenceRepository;
import fr.pivot.agilite.capacity.CapacityEvent;
import fr.pivot.agilite.capacity.CapacityEventMember;
import fr.pivot.agilite.capacity.CapacityEventMemberRepository;
import fr.pivot.agilite.capacity.CapacityEventRepository;
import fr.pivot.agilite.capacity.CapacityEventType;
import fr.pivot.agilite.capacity.CapacityVelocity;
import fr.pivot.agilite.capacity.CapacityVelocityRepository;
import fr.pivot.agilite.testsupport.PlatformAuthTestSupport;
import fr.pivot.agilite.testsupport.PlatformAuthTestSupport.AuthFixture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration tests for {@link KpiController}, exercising the full Spring context against a real
 * PostgreSQL database (Testcontainers) (EN11.2 — Capacity KPI, Wave 2).
 *
 * <p>Covers: the five KPI values on a seeded team (one closed sprint, one absence, one
 * over-committed event), the empty-team case ({@code capacity.velocite_moyenne} stays {@code
 * null}), the mandatory cross-tenant 404, and that the response never carries a member name/id
 * (RGPD — team-level aggregate only).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class KpiControllerIT extends AbstractAgiliteIntegrationTest {

    private static final String KPI_PATH = "/agilite/kpi";
    private static final Integer[] WORKING_DAYS = {1, 2, 3, 4, 5};

    @Autowired
    private WebApplicationContext wac;

    @Autowired
    private CapacityEventRepository eventRepository;

    @Autowired
    private CapacityEventMemberRepository eventMemberRepository;

    @Autowired
    private CapacityAbsenceRepository absenceRepository;

    @Autowired
    private CapacityVelocityRepository velocityRepository;

    private MockMvc mockMvc;

    private long tenantId;
    private long teamId;
    private String ownerToken;

    private String otherTenantToken;

    /**
     * Sets up MockMvc and seeds a tenant with a team and a member of that team, plus a user
     * belonging to an entirely separate tenant, before each test.
     */
    @BeforeEach
    void setUp() throws Exception {
        mockMvc = MockMvcBuilders.webAppContextSetup(wac).build();

        AuthFixture owner = PlatformAuthTestSupport.seedActiveUserWithTeamAndToken(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        tenantId = owner.tenantId();
        teamId = owner.teamId();
        ownerToken = owner.rawToken();

        AuthFixture otherTenant = PlatformAuthTestSupport.seedActiveUserWithToken(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        otherTenantToken = otherTenant.rawToken();
    }

    /**
     * Given a team with one closed-out sprint (with a delivered-velocity snapshot, one absence,
     * and committed points comfortably within its recommended engagement), when GET /kpi is
     * called, then all five KPIs are returned, aggregated for the team, with no member-level
     * data.
     */
    @Test
    void getKpis_seededTeam_returnsAggregatedKpis() throws Exception {
        // committedPoints kept well under the ~5.3-point recommended engagement (10 working
        // days, one full-day absence, default 0.70 focus / 0.15 margin maturity profile) so this
        // event does NOT count as a capacity.depassements overrun (see the dedicated test below).
        UUID sprintId = seedSprint("Sprint 1", LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 14), 3.0);
        UUID memberId = seedMember(sprintId, "Ada Lovelace", 1.0);
        seedAbsence(memberId, LocalDate.of(2026, 1, 5), LocalDate.of(2026, 1, 5));
        seedVelocity(sprintId, 10.0, 8.0);

        mockMvc.perform(
                        get(KPI_PATH)
                                .queryParam("eventId", sprintId.toString())
                                .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.teamId").value(teamId))
                .andExpect(jsonPath("$.eventSampleSize").value(1))
                .andExpect(jsonPath("$.sprintSampleSize").value(1))
                .andExpect(jsonPath("$.kpis['capacity.taux_utilisation']").isNumber())
                .andExpect(jsonPath("$.kpis['capacity.capacite_nette']").isNumber())
                .andExpect(jsonPath("$.kpis['capacity.velocite_moyenne']").value(8.0))
                .andExpect(jsonPath("$.kpis['capacity.taux_absence']").isNumber())
                .andExpect(jsonPath("$.kpis['capacity.depassements']").value(0.0))
                .andExpect(jsonPath("$..name").doesNotExist())
                .andExpect(jsonPath("$..memberId").doesNotExist());
    }

    /**
     * Given an event whose committed points exceed its recommended engagement, when GET /kpi is
     * called, then {@code capacity.depassements} counts it.
     */
    @Test
    void getKpis_overCommittedEvent_countsAsDepassement() throws Exception {
        UUID sprintId = seedSprint("Sprint Over", LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 14), 10_000.0);
        seedMember(sprintId, "Grace Hopper", 1.0);

        mockMvc.perform(
                        get(KPI_PATH)
                                .queryParam("eventId", sprintId.toString())
                                .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.kpis['capacity.depassements']").value(1.0));
    }

    /**
     * Given a team whose only event has no velocity snapshot, when GET /kpi is called, then
     * {@code capacity.velocite_moyenne} is {@code null} rather than {@code 0}.
     */
    @Test
    void getKpis_noVelocitySnapshot_returnsNullVelociteMoyenne() throws Exception {
        UUID sprintId = seedSprint("Sprint No Velocity", LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 14), 10.0);

        mockMvc.perform(
                        get(KPI_PATH)
                                .queryParam("eventId", sprintId.toString())
                                .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.kpis['capacity.velocite_moyenne']").doesNotExist());
    }

    /**
     * Given an event id belonging to a different tenant than the caller, when GET /kpi is
     * called, then it returns 404 (never confirms cross-tenant existence).
     */
    @Test
    void getKpis_crossTenant_returns404() throws Exception {
        UUID sprintId = seedSprint("Sprint Cross Tenant", LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 14), 10.0);

        mockMvc.perform(
                        get(KPI_PATH)
                                .queryParam("eventId", sprintId.toString())
                                .header("Authorization", "Bearer " + otherTenantToken))
                .andExpect(status().isNotFound());
    }

    /**
     * Given a caller belonging to the same tenant as the team but not one of its members, when
     * GET /kpi is called, then it also returns 404 (indistinguishable from a non-existent event).
     */
    @Test
    void getKpis_sameTenantNonMember_returns404() throws Exception {
        UUID sprintId = seedSprint("Sprint Non Member", LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 14), 10.0);

        long nonMemberUserId = PlatformAuthTestSupport.seedUser(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword(), tenantId, true);
        String nonMemberToken = PlatformAuthTestSupport.issueToken(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword(),
                nonMemberUserId, "active", Instant.now().plusSeconds(3600));

        mockMvc.perform(
                        get(KPI_PATH)
                                .queryParam("eventId", sprintId.toString())
                                .header("Authorization", "Bearer " + nonMemberToken))
                .andExpect(status().isNotFound());
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
        event.setPointsPerDay(1.0);
        return eventRepository.save(event).getId();
    }

    /** Seeds an event member directly via the repository. */
    private UUID seedMember(final UUID eventId, final String name, final double quotite) {
        CapacityEventMember member = new CapacityEventMember(eventId, null, name, "DEV", quotite, 0);
        return eventMemberRepository.save(member).getId();
    }

    /** Seeds a full-day absence directly via the repository. */
    private void seedAbsence(final UUID eventMemberId, final LocalDate startDate, final LocalDate endDate) {
        absenceRepository.save(new CapacityAbsence(
                eventMemberId, startDate, endDate, CapacityAbsence.FRACTION_FULL_DAY,
                CapacityAbsence.SOURCE_MANUAL, Instant.now()));
    }

    /** Seeds a velocity snapshot directly via the repository. */
    private void seedVelocity(final UUID sprintEventId, final double pointsEngages, final double pointsLivres) {
        velocityRepository.save(new CapacityVelocity(sprintEventId, pointsEngages, pointsLivres, Instant.now()));
    }
}

package fr.pivot.agilite.capacity;

import fr.pivot.agilite.AbstractAgiliteIntegrationTest;
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

import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration tests for {@link CapacitySummaryController} (F11.6.5 + F11.6.6), against the full
 * Spring context (Testcontainers PostgreSQL/Redis) — mirrors {@code
 * RetroActionControllerIT}'s setup.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class CapacitySummaryControllerIT extends AbstractAgiliteIntegrationTest {

    @Autowired
    private WebApplicationContext wac;

    @Autowired
    private CapacityEventRepository eventRepository;

    @Autowired
    private CapacityEventMemberRepository eventMemberRepository;

    @Autowired
    private CapacityAbsenceRepository absenceRepository;

    private MockMvc mockMvc;

    private String memberToken;
    private long tenantId;
    private long teamId;
    private String otherTenantToken;
    private String nonMemberToken;

    @BeforeEach
    void setUp() throws Exception {
        mockMvc = MockMvcBuilders.webAppContextSetup(wac).build();

        AuthFixture member = PlatformAuthTestSupport.seedActiveUserWithTeamAndToken(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        memberToken = member.rawToken();
        tenantId = member.tenantId();
        teamId = member.teamId();

        AuthFixture otherTenant = PlatformAuthTestSupport.seedActiveUserWithToken(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        otherTenantToken = otherTenant.rawToken();

        long nonMemberId = PlatformAuthTestSupport.seedUser(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword(), tenantId, true);
        nonMemberToken = PlatformAuthTestSupport.issueToken(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword(),
                nonMemberId, "active", Instant.now().plusSeconds(3600));
    }

    // -------------------------------------------------------------------------
    // GET /agilite/capacity/events/{id}/summary
    // -------------------------------------------------------------------------

    @Test
    void summary_oneMemberOneAbsence_matchesCalculatorNumbers() throws Exception {
        // 2-week sprint (Mon..Fri), 1 member full-time, no per-event overrides (default profile:
        // focus 0.70, margin 0.15), 1 full-day absence.
        // totalWorkingDays = 10, absentDays = 1 -> joursHommeNets = (10-1)*1.0 = 9.0
        // capaciteNette = 9.0*0.70 = 6.3, engagementRecommande = 6.3*(1-0.15) = 5.36 (rounded)
        CapacityEvent event = new CapacityEvent(
                tenantId, teamId, CapacityEventType.SPRINT, "Sprint 1",
                LocalDate.of(2026, 7, 6), LocalDate.of(2026, 7, 17), new Integer[] {1, 2, 3, 4, 5});
        event = eventRepository.save(event);

        CapacityEventMember member = eventMemberRepository.save(
                new CapacityEventMember(event.getId(), null, "Alice", "DEV", 1.0, 0));
        absenceRepository.save(new CapacityAbsence(
                member.getId(), LocalDate.of(2026, 7, 7), LocalDate.of(2026, 7, 7),
                CapacityAbsence.FRACTION_FULL_DAY, CapacityAbsence.SOURCE_MANUAL, Instant.now()));

        mockMvc.perform(
                        get("/agilite/capacity/events/" + event.getId() + "/summary")
                                .header("Authorization", "Bearer " + memberToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalWorkingDays").value(10))
                .andExpect(jsonPath("$.totalNetPersonDays").value(9.0))
                .andExpect(jsonPath("$.totalNetCapacity").value(6.3))
                .andExpect(jsonPath("$.totalRecommendedEngagement").value(5.36))
                .andExpect(jsonPath("$.members.length()").value(1))
                .andExpect(jsonPath("$.members[0].name").value("Alice"))
                .andExpect(jsonPath("$.members[0].absentWorkingDays").value(1.0))
                .andExpect(jsonPath("$.consolidation").value(nullValue()));
    }

    @Test
    void summary_committedPointsAboveRecommendedEngagement_gaugeOverCommitted() throws Exception {
        CapacityEvent event = new CapacityEvent(
                tenantId, teamId, CapacityEventType.SPRINT, "Sprint 1",
                LocalDate.of(2026, 7, 6), LocalDate.of(2026, 7, 17), new Integer[] {1, 2, 3, 4, 5});
        event.setCommittedPoints(100.0); // way above Alice's recommended engagement (5.95)
        event = eventRepository.save(event);
        eventMemberRepository.save(new CapacityEventMember(event.getId(), null, "Alice", "DEV", 1.0, 0));

        mockMvc.perform(
                        get("/agilite/capacity/events/" + event.getId() + "/summary")
                                .header("Authorization", "Bearer " + memberToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.gauge.engagedPoints").value(100.0))
                .andExpect(jsonPath("$.gauge.overCommitted").value(true));
    }

    @Test
    void summary_committedPointsBelowRecommendedEngagement_gaugeNotOverCommitted() throws Exception {
        CapacityEvent event = new CapacityEvent(
                tenantId, teamId, CapacityEventType.SPRINT, "Sprint 1",
                LocalDate.of(2026, 7, 6), LocalDate.of(2026, 7, 17), new Integer[] {1, 2, 3, 4, 5});
        event.setCommittedPoints(1.0);
        event = eventRepository.save(event);
        eventMemberRepository.save(new CapacityEventMember(event.getId(), null, "Alice", "DEV", 1.0, 0));

        mockMvc.perform(
                        get("/agilite/capacity/events/" + event.getId() + "/summary")
                                .header("Authorization", "Bearer " + memberToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.gauge.overCommitted").value(false));
    }

    @Test
    void summary_piWithSprintChildren_consolidatesTotals() throws Exception {
        CapacityEvent pi = new CapacityEvent(
                tenantId, teamId, CapacityEventType.PI_PLANNING, "PI 1",
                LocalDate.of(2026, 7, 6), LocalDate.of(2026, 8, 14), new Integer[] {1, 2, 3, 4, 5});
        pi = eventRepository.save(pi);

        CapacityEvent sprint1 = new CapacityEvent(
                tenantId, teamId, CapacityEventType.SPRINT, "Sprint 1",
                LocalDate.of(2026, 7, 6), LocalDate.of(2026, 7, 17), new Integer[] {1, 2, 3, 4, 5});
        sprint1.setParentId(pi.getId());
        sprint1 = eventRepository.save(sprint1);
        eventMemberRepository.save(new CapacityEventMember(sprint1.getId(), null, "Alice", "DEV", 1.0, 0));

        CapacityEvent sprint2 = new CapacityEvent(
                tenantId, teamId, CapacityEventType.SPRINT, "IP Sprint",
                LocalDate.of(2026, 7, 20), LocalDate.of(2026, 7, 31), new Integer[] {1, 2, 3, 4, 5});
        sprint2.setParentId(pi.getId());
        sprint2.setIpSprint(true);
        sprint2 = eventRepository.save(sprint2);
        eventMemberRepository.save(new CapacityEventMember(sprint2.getId(), null, "Bob", "DEV", 1.0, 0));

        mockMvc.perform(
                        get("/agilite/capacity/events/" + pi.getId() + "/summary")
                                .header("Authorization", "Bearer " + memberToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.consolidation.includedSprintCount").value(1))
                .andExpect(jsonPath("$.consolidation.excludedIpSprintCount").value(1))
                .andExpect(jsonPath("$.consolidation.totalCapaciteNette").value(7.0));
    }

    @Test
    void summary_unknownEvent_returns404() throws Exception {
        mockMvc.perform(
                        get("/agilite/capacity/events/" + UUID.randomUUID() + "/summary")
                                .header("Authorization", "Bearer " + memberToken))
                .andExpect(status().isNotFound());
    }

    @Test
    void summary_crossTenantEvent_returns404() throws Exception {
        CapacityEvent event = eventRepository.save(new CapacityEvent(
                tenantId, teamId, CapacityEventType.SPRINT, "Sprint 1",
                LocalDate.of(2026, 7, 6), LocalDate.of(2026, 7, 17), new Integer[] {1, 2, 3, 4, 5}));

        mockMvc.perform(
                        get("/agilite/capacity/events/" + event.getId() + "/summary")
                                .header("Authorization", "Bearer " + otherTenantToken))
                .andExpect(status().isNotFound());
    }

    @Test
    void summary_callerNotTeamMember_returns404() throws Exception {
        CapacityEvent event = eventRepository.save(new CapacityEvent(
                tenantId, teamId, CapacityEventType.SPRINT, "Sprint 1",
                LocalDate.of(2026, 7, 6), LocalDate.of(2026, 7, 17), new Integer[] {1, 2, 3, 4, 5}));

        mockMvc.perform(
                        get("/agilite/capacity/events/" + event.getId() + "/summary")
                                .header("Authorization", "Bearer " + nonMemberToken))
                .andExpect(status().isNotFound());
    }

    @Test
    void summary_noAuthHeader_returns401() throws Exception {
        CapacityEvent event = eventRepository.save(new CapacityEvent(
                tenantId, teamId, CapacityEventType.SPRINT, "Sprint 1",
                LocalDate.of(2026, 7, 6), LocalDate.of(2026, 7, 17), new Integer[] {1, 2, 3, 4, 5}));

        mockMvc.perform(get("/agilite/capacity/events/" + event.getId() + "/summary"))
                .andExpect(status().isUnauthorized());
    }
}

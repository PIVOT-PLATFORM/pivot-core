package fr.pivot.agilite.capacity.rgpd;

import fr.pivot.agilite.AbstractAgiliteIntegrationTest;
import fr.pivot.agilite.capacity.CapacityAbsence;
import fr.pivot.agilite.capacity.CapacityAbsenceRepository;
import fr.pivot.agilite.capacity.CapacityEvent;
import fr.pivot.agilite.capacity.CapacityEventMember;
import fr.pivot.agilite.capacity.CapacityEventMemberRepository;
import fr.pivot.agilite.capacity.CapacityEventRepository;
import fr.pivot.agilite.capacity.CapacityEventType;
import fr.pivot.agilite.testsupport.PlatformAuthTestSupport;
import fr.pivot.agilite.testsupport.PlatformAuthTestSupport.AuthFixture;
import fr.pivot.core.team.TeamMember;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration tests for {@link CapacityRgpdController} (US11.8.1) — data-subject rights of
 * access/portability and erasure, against the full Spring context (Testcontainers
 * PostgreSQL/Redis), mirroring {@code CapacityMemberControllerIT}'s setup.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class CapacityRgpdControllerIT extends AbstractAgiliteIntegrationTest {

    private static final Integer[] WORKING_DAYS = {1, 2, 3, 4, 5};

    @Autowired
    private WebApplicationContext wac;

    @Autowired
    private CapacityEventRepository eventRepository;

    @Autowired
    private CapacityEventMemberRepository memberRepository;

    @Autowired
    private CapacityAbsenceRepository absenceRepository;

    private MockMvc mockMvc;

    private long tenantId;
    private long teamId;

    /** Caller with {@link TeamMember#ROLE_RESPONSABLE} — write-capable, can erase. */
    private String responsableToken;

    /** Caller with default {@link TeamMember#ROLE_MEMBRE} — read-only, erasure forbidden. */
    private String membreToken;

    /** Fully separate tenant — used for the cross-tenant isolation test. */
    private String otherTenantToken;

    /** The data subject's {@code public.team_members.id} — the path variable under test. */
    private long teamMemberRef;

    private UUID absenceId;

    @BeforeEach
    void setUp() throws Exception {
        mockMvc = MockMvcBuilders.webAppContextSetup(wac).build();

        AuthFixture responsable = PlatformAuthTestSupport.seedActiveUserWithToken(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        responsableToken = responsable.rawToken();
        tenantId = responsable.tenantId();
        teamId = PlatformAuthTestSupport.seedTeam(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword(),
                tenantId, "Team " + UUID.randomUUID());
        PlatformAuthTestSupport.seedTeamMember(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword(),
                teamId, responsable.userId());
        promoteToResponsable(teamId, responsable.userId());

        long membreId = PlatformAuthTestSupport.seedUser(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword(), tenantId, true);
        PlatformAuthTestSupport.seedTeamMember(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword(), teamId, membreId);
        membreToken = PlatformAuthTestSupport.issueToken(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword(),
                membreId, "active", Instant.now().plusSeconds(3600));

        AuthFixture otherTenant = PlatformAuthTestSupport.seedActiveUserWithToken(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        otherTenantToken = otherTenant.rawToken();

        long dataSubjectUserId = PlatformAuthTestSupport.seedUser(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword(), tenantId, true);
        teamMemberRef = PlatformAuthTestSupport.seedTeamMember(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword(), teamId, dataSubjectUserId);

        UUID eventId = eventRepository.save(new CapacityEvent(
                tenantId, teamId, CapacityEventType.SPRINT, "Sprint 1",
                LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 14), WORKING_DAYS)).getId();

        CapacityEventMember member = memberRepository.save(new CapacityEventMember(
                eventId, teamMemberRef, "Alice", "Dev", 1.0, 0));

        absenceId = absenceRepository.save(new CapacityAbsence(
                        member.getId(), LocalDate.of(2026, 8, 3), LocalDate.of(2026, 8, 3), 1.0,
                        CapacityAbsence.SOURCE_MANUAL, Instant.now()))
                .getId();
    }

    // -------------------------------------------------------------------------
    // GET /agilite/capacity/rgpd/members/{teamMemberRef}/data
    // -------------------------------------------------------------------------

    @Test
    void exportData_asResponsable_returnsOnlyThatPersonsAbsences() throws Exception {
        mockMvc.perform(
                        get("/agilite/capacity/rgpd/members/" + teamMemberRef + "/data")
                                .header("Authorization", "Bearer " + responsableToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.teamMemberRef").value(teamMemberRef))
                .andExpect(jsonPath("$.absences.length()").value(1))
                .andExpect(jsonPath("$.absences[0].id").value(absenceId.toString()))
                .andExpect(jsonPath("$.absences[0].startDate").value("2026-08-03"))
                .andExpect(jsonPath("$.absences[0].endDate").value("2026-08-03"))
                .andExpect(jsonPath("$.absences[0].fraction").value(1.0))
                .andExpect(jsonPath("$.absences[0].source").value("MANUAL"))
                // Aggregate-safe: no motif/health/name field ever exposed — only the schema-level
                // fields CapacityAbsence itself carries.
                .andExpect(jsonPath("$.absences[0].reason").doesNotExist())
                .andExpect(jsonPath("$.absences[0].motif").doesNotExist())
                .andExpect(jsonPath("$.absences[0].name").doesNotExist());
    }

    @Test
    void exportData_asMembre_returns200() throws Exception {
        // Access/portability requires only tenant + team-membership, not a write-capable role.
        mockMvc.perform(
                        get("/agilite/capacity/rgpd/members/" + teamMemberRef + "/data")
                                .header("Authorization", "Bearer " + membreToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.absences.length()").value(1));
    }

    @Test
    void exportData_crossTenantCaller_returns404() throws Exception {
        mockMvc.perform(
                        get("/agilite/capacity/rgpd/members/" + teamMemberRef + "/data")
                                .header("Authorization", "Bearer " + otherTenantToken))
                .andExpect(status().isNotFound());
    }

    @Test
    void exportData_unknownTeamMemberRef_returns404() throws Exception {
        mockMvc.perform(
                        get("/agilite/capacity/rgpd/members/999999999/data")
                                .header("Authorization", "Bearer " + responsableToken))
                .andExpect(status().isNotFound());
    }

    // -------------------------------------------------------------------------
    // DELETE /agilite/capacity/rgpd/members/{teamMemberRef}/data
    // -------------------------------------------------------------------------

    @Test
    void eraseData_asResponsable_returns204AndRemovesAbsences() throws Exception {
        mockMvc.perform(
                        delete("/agilite/capacity/rgpd/members/" + teamMemberRef + "/data")
                                .header("Authorization", "Bearer " + responsableToken))
                .andExpect(status().isNoContent());

        Assertions.assertTrue(absenceRepository.findById(absenceId).isEmpty());
    }

    @Test
    void eraseData_asMembre_returns403() throws Exception {
        mockMvc.perform(
                        delete("/agilite/capacity/rgpd/members/" + teamMemberRef + "/data")
                                .header("Authorization", "Bearer " + membreToken))
                .andExpect(status().isForbidden());

        Assertions.assertTrue(absenceRepository.findById(absenceId).isPresent());
    }

    @Test
    void eraseData_crossTenantCaller_returns404() throws Exception {
        mockMvc.perform(
                        delete("/agilite/capacity/rgpd/members/" + teamMemberRef + "/data")
                                .header("Authorization", "Bearer " + otherTenantToken))
                .andExpect(status().isNotFound());

        Assertions.assertTrue(absenceRepository.findById(absenceId).isPresent());
    }

    /**
     * Promotes a seeded team membership to {@link TeamMember#ROLE_RESPONSABLE} — same
     * test-only-seeding posture as {@code CapacityMemberControllerIT}.
     *
     * @param teamId the team id
     * @param userId the user id whose membership row is promoted
     * @throws SQLException if the update fails
     */
    private void promoteToResponsable(final long teamId, final long userId) throws SQLException {
        final String sql = "UPDATE public.team_members SET role = ? WHERE team_id = ? AND user_id = ?";
        try (Connection conn = DriverManager.getConnection(
                        postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
                PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, TeamMember.ROLE_RESPONSABLE);
            ps.setLong(2, teamId);
            ps.setLong(3, userId);
            ps.executeUpdate();
        }
    }
}

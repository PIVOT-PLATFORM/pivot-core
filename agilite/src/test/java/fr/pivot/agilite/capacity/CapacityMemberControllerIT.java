package fr.pivot.agilite.capacity;

import fr.pivot.agilite.AbstractAgiliteIntegrationTest;
import fr.pivot.agilite.testsupport.PlatformAuthTestSupport;
import fr.pivot.agilite.testsupport.PlatformAuthTestSupport.AuthFixture;
import fr.pivot.core.team.TeamMember;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.ObjectMapper;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration tests for {@link CapacityMemberController} (F11.2), against the full Spring
 * context (Testcontainers PostgreSQL/Redis) — mirrors {@code RetroActionControllerIT}'s setup.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class CapacityMemberControllerIT extends AbstractAgiliteIntegrationTest {

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
    private final ObjectMapper objectMapper = new ObjectMapper();

    private long tenantId;
    private long teamId;

    /** Caller with {@link TeamMember#ROLE_RESPONSABLE} — the OWNER-equivalent, write-capable. */
    private String responsableToken;
    private long responsableId;

    /** Caller with default {@link TeamMember#ROLE_MEMBRE} — the VIEWER-equivalent, read-only. */
    private String membreToken;

    /** Fully separate tenant — used for the cross-tenant isolation tests. */
    private String otherTenantToken;

    private UUID eventId;

    @BeforeEach
    void setUp() throws Exception {
        mockMvc = MockMvcBuilders.webAppContextSetup(wac).build();

        AuthFixture responsable = PlatformAuthTestSupport.seedActiveUserWithToken(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        responsableToken = responsable.rawToken();
        responsableId = responsable.userId();
        tenantId = responsable.tenantId();
        teamId = PlatformAuthTestSupport.seedTeam(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword(),
                tenantId, "Team " + UUID.randomUUID());
        PlatformAuthTestSupport.seedTeamMember(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword(), teamId, responsableId);
        promoteToResponsable(teamId, responsableId);

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

        eventId = eventRepository.save(new CapacityEvent(
                tenantId, teamId, CapacityEventType.SPRINT, "Sprint 1",
                LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 14), WORKING_DAYS)).getId();
    }

    // -------------------------------------------------------------------------
    // POST /agilite/capacity/events/{eventId}/members
    // -------------------------------------------------------------------------

    @Test
    void addMember_asResponsable_returns201() throws Exception {
        mockMvc.perform(
                        post("/agilite/capacity/events/" + eventId + "/members")
                                .header("Authorization", "Bearer " + responsableToken)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                                        {"name":"Alice","role":"Dev","quotite":1.0}
                                        """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("Alice"))
                .andExpect(jsonPath("$.eventId").value(eventId.toString()))
                .andExpect(jsonPath("$.quotite").value(1.0))
                .andExpect(jsonPath("$.excluded").value(false));
    }

    @Test
    void addMember_asMembre_returns403() throws Exception {
        mockMvc.perform(
                        post("/agilite/capacity/events/" + eventId + "/members")
                                .header("Authorization", "Bearer " + membreToken)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                                        {"name":"Alice","quotite":1.0}
                                        """))
                .andExpect(status().isForbidden());
    }

    @Test
    void addMember_invalidQuotite_returns400() throws Exception {
        mockMvc.perform(
                        post("/agilite/capacity/events/" + eventId + "/members")
                                .header("Authorization", "Bearer " + responsableToken)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                                        {"name":"Alice","quotite":1.5}
                                        """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_QUOTITE"));
    }

    @Test
    void addMember_crossTenantEvent_returns404() throws Exception {
        mockMvc.perform(
                        post("/agilite/capacity/events/" + eventId + "/members")
                                .header("Authorization", "Bearer " + otherTenantToken)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                                        {"name":"Alice","quotite":1.0}
                                        """))
                .andExpect(status().isNotFound());
    }

    @Test
    void addMember_unknownEvent_returns404() throws Exception {
        mockMvc.perform(
                        post("/agilite/capacity/events/" + UUID.randomUUID() + "/members")
                                .header("Authorization", "Bearer " + responsableToken)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                                        {"name":"Alice","quotite":1.0}
                                        """))
                .andExpect(status().isNotFound());
    }

    // -------------------------------------------------------------------------
    // PUT /agilite/capacity/members/{memberId}
    // -------------------------------------------------------------------------

    @Test
    void updateMember_asResponsable_returns200() throws Exception {
        String memberId = addMember("Alice", 1.0);

        mockMvc.perform(
                        put("/agilite/capacity/members/" + memberId)
                                .header("Authorization", "Bearer " + responsableToken)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                                        {"name":"Alice B.","quotite":0.5,"excluded":true}
                                        """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Alice B."))
                .andExpect(jsonPath("$.quotite").value(0.5))
                .andExpect(jsonPath("$.excluded").value(true));
    }

    @Test
    void updateMember_asMembre_returns403() throws Exception {
        String memberId = addMember("Alice", 1.0);

        mockMvc.perform(
                        put("/agilite/capacity/members/" + memberId)
                                .header("Authorization", "Bearer " + membreToken)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                                        {"name":"Alice B.","quotite":0.5}
                                        """))
                .andExpect(status().isForbidden());
    }

    @Test
    void updateMember_crossTenantMember_returns404() throws Exception {
        String memberId = addMember("Alice", 1.0);

        mockMvc.perform(
                        put("/agilite/capacity/members/" + memberId)
                                .header("Authorization", "Bearer " + otherTenantToken)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                                        {"name":"Alice B.","quotite":0.5}
                                        """))
                .andExpect(status().isNotFound());
    }

    // -------------------------------------------------------------------------
    // DELETE /agilite/capacity/members/{memberId}
    // -------------------------------------------------------------------------

    @Test
    void deleteMember_asResponsable_returns204AndCascadesAbsences() throws Exception {
        String memberId = addMember("Alice", 1.0);
        String absenceId = addAbsence(memberId, "2026-08-03", "2026-08-03", 1);

        mockMvc.perform(
                        delete("/agilite/capacity/members/" + memberId)
                                .header("Authorization", "Bearer " + responsableToken))
                .andExpect(status().isNoContent());

        Assertions.assertTrue(
                memberRepository.findById(UUID.fromString(memberId)).isEmpty());
        Assertions.assertTrue(
                absenceRepository.findById(UUID.fromString(absenceId)).isEmpty());
    }

    @Test
    void deleteMember_asMembre_returns403() throws Exception {
        String memberId = addMember("Alice", 1.0);

        mockMvc.perform(
                        delete("/agilite/capacity/members/" + memberId)
                                .header("Authorization", "Bearer " + membreToken))
                .andExpect(status().isForbidden());
    }

    // -------------------------------------------------------------------------
    // POST /agilite/capacity/members/{memberId}/absences
    // -------------------------------------------------------------------------

    @Test
    void addAbsence_asResponsable_returns201() throws Exception {
        String memberId = addMember("Alice", 1.0);

        mockMvc.perform(
                        post("/agilite/capacity/members/" + memberId + "/absences")
                                .header("Authorization", "Bearer " + responsableToken)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                                        {"startDate":"2026-08-03","endDate":"2026-08-04","fraction":1}
                                        """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.eventMemberId").value(memberId))
                .andExpect(jsonPath("$.source").value("MANUAL"));
    }

    @Test
    void addAbsence_asMembre_returns403() throws Exception {
        String memberId = addMember("Alice", 1.0);

        mockMvc.perform(
                        post("/agilite/capacity/members/" + memberId + "/absences")
                                .header("Authorization", "Bearer " + membreToken)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                                        {"startDate":"2026-08-03","endDate":"2026-08-04","fraction":1}
                                        """))
                .andExpect(status().isForbidden());
    }

    @Test
    void addAbsence_invalidDateRange_returns400() throws Exception {
        String memberId = addMember("Alice", 1.0);

        mockMvc.perform(
                        post("/agilite/capacity/members/" + memberId + "/absences")
                                .header("Authorization", "Bearer " + responsableToken)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                                        {"startDate":"2026-08-04","endDate":"2026-08-03","fraction":1}
                                        """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_DATE_RANGE"));
    }

    @Test
    void addAbsence_outsideEventWindow_returns400() throws Exception {
        String memberId = addMember("Alice", 1.0);

        mockMvc.perform(
                        post("/agilite/capacity/members/" + memberId + "/absences")
                                .header("Authorization", "Bearer " + responsableToken)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                                        {"startDate":"2026-09-01","endDate":"2026-09-02","fraction":1}
                                        """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("ABSENCE_OUT_OF_RANGE"));
    }

    @Test
    void addAbsence_invalidFraction_returns400() throws Exception {
        String memberId = addMember("Alice", 1.0);

        mockMvc.perform(
                        post("/agilite/capacity/members/" + memberId + "/absences")
                                .header("Authorization", "Bearer " + responsableToken)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                                        {"startDate":"2026-08-03","endDate":"2026-08-03","fraction":0.25}
                                        """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_FRACTION"));
    }

    @Test
    void addAbsence_crossTenantMember_returns404() throws Exception {
        String memberId = addMember("Alice", 1.0);

        mockMvc.perform(
                        post("/agilite/capacity/members/" + memberId + "/absences")
                                .header("Authorization", "Bearer " + otherTenantToken)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                                        {"startDate":"2026-08-03","endDate":"2026-08-04","fraction":1}
                                        """))
                .andExpect(status().isNotFound());
    }

    // -------------------------------------------------------------------------
    // DELETE /agilite/capacity/absences/{absenceId}
    // -------------------------------------------------------------------------

    @Test
    void deleteAbsence_asResponsable_returns204() throws Exception {
        String memberId = addMember("Alice", 1.0);
        String absenceId = addAbsence(memberId, "2026-08-03", "2026-08-03", 1);

        mockMvc.perform(
                        delete("/agilite/capacity/absences/" + absenceId)
                                .header("Authorization", "Bearer " + responsableToken))
                .andExpect(status().isNoContent());

        Assertions.assertTrue(
                absenceRepository.findById(UUID.fromString(absenceId)).isEmpty());
    }

    @Test
    void deleteAbsence_asMembre_returns403() throws Exception {
        String memberId = addMember("Alice", 1.0);
        String absenceId = addAbsence(memberId, "2026-08-03", "2026-08-03", 1);

        mockMvc.perform(
                        delete("/agilite/capacity/absences/" + absenceId)
                                .header("Authorization", "Bearer " + membreToken))
                .andExpect(status().isForbidden());
    }

    /**
     * Mandatory cross-tenant test: a tenant B caller must get 404 (never a distinguishable
     * response) when operating on tenant A's member and absence.
     */
    @Test
    void crossTenantCaller_operatingOnAnotherTenantsMemberAndAbsence_returns404() throws Exception {
        String memberId = addMember("Alice", 1.0);
        String absenceId = addAbsence(memberId, "2026-08-03", "2026-08-03", 1);

        mockMvc.perform(
                        put("/agilite/capacity/members/" + memberId)
                                .header("Authorization", "Bearer " + otherTenantToken)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                                        {"name":"Hacked","quotite":1.0}
                                        """))
                .andExpect(status().isNotFound());

        mockMvc.perform(
                        delete("/agilite/capacity/members/" + memberId)
                                .header("Authorization", "Bearer " + otherTenantToken))
                .andExpect(status().isNotFound());

        mockMvc.perform(
                        delete("/agilite/capacity/absences/" + absenceId)
                                .header("Authorization", "Bearer " + otherTenantToken))
                .andExpect(status().isNotFound());

        mockMvc.perform(
                        post("/agilite/capacity/members/" + memberId + "/absences")
                                .header("Authorization", "Bearer " + otherTenantToken)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                                        {"startDate":"2026-08-03","endDate":"2026-08-03","fraction":1}
                                        """))
                .andExpect(status().isNotFound());
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private String addMember(final String name, final double quotite) throws Exception {
        MvcResult result = mockMvc.perform(
                        post("/agilite/capacity/events/" + eventId + "/members")
                                .header("Authorization", "Bearer " + responsableToken)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                                        {"name":"%s","quotite":%s}
                                        """.formatted(name, quotite)))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asText();
    }

    private String addAbsence(
            final String memberId, final String startDate, final String endDate, final double fraction)
            throws Exception {
        MvcResult result = mockMvc.perform(
                        post("/agilite/capacity/members/" + memberId + "/absences")
                                .header("Authorization", "Bearer " + responsableToken)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                                        {"startDate":"%s","endDate":"%s","fraction":%s}
                                        """.formatted(startDate, endDate, fraction)))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asText();
    }

    /**
     * Promotes a seeded team membership to {@link TeamMember#ROLE_RESPONSABLE} — the
     * OWNER-equivalent, write-capable role. {@link PlatformAuthTestSupport} has no helper for
     * this (it always seeds the default {@link TeamMember#ROLE_MEMBRE}), so this test issues the
     * raw-JDBC {@code UPDATE} directly, same test-only-seeding posture as {@link
     * PlatformAuthTestSupport} itself.
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

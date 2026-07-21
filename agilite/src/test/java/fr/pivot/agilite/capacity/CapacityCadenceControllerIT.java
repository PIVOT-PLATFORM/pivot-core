package fr.pivot.agilite.capacity;

import fr.pivot.agilite.AbstractAgiliteIntegrationTest;
import fr.pivot.agilite.testsupport.PlatformAuthTestSupport;
import fr.pivot.agilite.testsupport.PlatformAuthTestSupport.AuthFixture;
import fr.pivot.core.team.TeamMember;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.time.Instant;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration tests for {@link CapacityEventController}'s cadence endpoint ({@code POST
 * /capacity/events/{piId}/cadence}, F11.5 — PI/SAFe cadence, auto side) exercising the full
 * Spring context against a real PostgreSQL database.
 *
 * <p>A separate file from {@code CapacityEventControllerIT} per this wave's file-ownership rules
 * — that class is not edited here.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class CapacityCadenceControllerIT extends AbstractAgiliteIntegrationTest {

    private static final String EVENTS_PATH = "/agilite/capacity/events";

    @Autowired
    private WebApplicationContext wac;

    private MockMvc mockMvc;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private String responsableToken;
    private long responsableId;
    private long teamId;
    private long tenantId;
    private String otherTenantToken;

    /**
     * Sets up MockMvc and seeds a tenant with a team, a {@code RESPONSABLE} member of that team
     * (write-capable), and a user belonging to an entirely separate tenant.
     */
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
        seedTeamMemberWithRole(teamId, responsableId, TeamMember.ROLE_RESPONSABLE);

        AuthFixture otherTenant = PlatformAuthTestSupport.seedActiveUserWithToken(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        otherTenantToken = otherTenant.rawToken();
    }

    // -------------------------------------------------------------------------
    // Happy path
    // -------------------------------------------------------------------------

    @Test
    void generateCadence_asWriteCapableMember_createsSequentialSprintsUnderPi() throws Exception {
        String piId = createEvent(responsableToken, "PI_PLANNING", "PI 2026-Q1",
                "2026-01-05", "2026-03-29", null);

        mockMvc.perform(
                        post(EVENTS_PATH + "/" + piId + "/cadence")
                                .header("Authorization", "Bearer " + responsableToken)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                                        {"sprintLengthDays":14,"sprintCount":3,"includeIpSprint":false}
                                        """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.length()").value(3))
                .andExpect(jsonPath("$[0].name").value("Sprint 1"))
                .andExpect(jsonPath("$[0].startDate").value("2026-01-05"))
                .andExpect(jsonPath("$[0].endDate").value("2026-01-18"))
                .andExpect(jsonPath("$[0].ipSprint").value(false))
                .andExpect(jsonPath("$[2].name").value("Sprint 3"))
                .andExpect(jsonPath("$[2].endDate").value("2026-02-15"));

        mockMvc.perform(
                        get(EVENTS_PATH + "/" + piId + "/children")
                                .header("Authorization", "Bearer " + responsableToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(3));
    }

    @Test
    void generateCadence_withIpSprint_marksTrailingSprintAsIp() throws Exception {
        String piId = createEvent(responsableToken, "PI_PLANNING", "PI 2026-Q2",
                "2026-04-06", "2026-06-28", null);

        mockMvc.perform(
                        post(EVENTS_PATH + "/" + piId + "/cadence")
                                .header("Authorization", "Bearer " + responsableToken)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                                        {"sprintLengthWeeks":2,"sprintCount":3,"includeIpSprint":true}
                                        """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.length()").value(4))
                .andExpect(jsonPath("$[3].ipSprint").value(true))
                .andExpect(jsonPath("$[3].name").value("Sprint 4 (IP)"))
                .andExpect(jsonPath("$[0].ipSprint").value(false));
    }

    // -------------------------------------------------------------------------
    // Validation
    // -------------------------------------------------------------------------

    @Test
    void generateCadence_targetNotAPi_returns400WithNotAPi() throws Exception {
        String sprintId = createEvent(responsableToken, "SPRINT", "Standalone sprint",
                "2026-01-05", "2026-01-18", null);

        mockMvc.perform(
                        post(EVENTS_PATH + "/" + sprintId + "/cadence")
                                .header("Authorization", "Bearer " + responsableToken)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                                        {"sprintLengthDays":14,"sprintCount":1,"includeIpSprint":false}
                                        """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("NOT_A_PI"));
    }

    @Test
    void generateCadence_overflowingWindow_returns400WithCadenceOverflow() throws Exception {
        String piId = createEvent(responsableToken, "PI_PLANNING", "Tight PI",
                "2026-01-05", "2026-01-18", null);

        mockMvc.perform(
                        post(EVENTS_PATH + "/" + piId + "/cadence")
                                .header("Authorization", "Bearer " + responsableToken)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                                        {"sprintLengthDays":14,"sprintCount":2,"includeIpSprint":false}
                                        """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("CADENCE_OVERFLOW"));
    }

    // -------------------------------------------------------------------------
    // Role gate
    // -------------------------------------------------------------------------

    @Test
    void generateCadence_asReadOnlyMember_returns403() throws Exception {
        String piId = createEvent(responsableToken, "PI_PLANNING", "PI for reader",
                "2026-01-05", "2026-03-29", null);
        String readerToken = seedTeamMemberWithRoleAndToken(TeamMember.ROLE_MEMBRE);

        mockMvc.perform(
                        post(EVENTS_PATH + "/" + piId + "/cadence")
                                .header("Authorization", "Bearer " + readerToken)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                                        {"sprintLengthDays":14,"sprintCount":3,"includeIpSprint":false}
                                        """))
                .andExpect(status().isForbidden());
    }

    // -------------------------------------------------------------------------
    // Tenant isolation
    // -------------------------------------------------------------------------

    @Test
    void generateCadence_crossTenant_returns404() throws Exception {
        String piId = createEvent(responsableToken, "PI_PLANNING", "Cross-tenant PI",
                "2026-01-05", "2026-03-29", null);

        mockMvc.perform(
                        post(EVENTS_PATH + "/" + piId + "/cadence")
                                .header("Authorization", "Bearer " + otherTenantToken)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                                        {"sprintLengthDays":14,"sprintCount":3,"includeIpSprint":false}
                                        """))
                .andExpect(status().isNotFound());
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /** Creates an event via the API and returns its id. */
    private String createEvent(
            final String token, final String type, final String name,
            final String startDate, final String endDate, final String parentId) throws Exception {
        String parentField = parentId != null ? "\"parentId\":\"" + parentId + "\"," : "";
        MvcResult result = mockMvc.perform(
                        post(EVENTS_PATH)
                                .header("Authorization", "Bearer " + token)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                                        {"teamId":%d,"type":"%s","name":"%s",%s
                                         "startDate":"%s","endDate":"%s"}
                                        """.formatted(teamId, type, name, parentField, startDate, endDate)))
                .andExpect(status().isCreated())
                .andReturn();
        JsonNode node = objectMapper.readTree(result.getResponse().getContentAsString());
        return node.get("id").asText();
    }

    /** Seeds a team membership row then overrides its {@code role} column via raw JDBC. */
    private void seedTeamMemberWithRole(final long teamId, final long userId, final String role) throws Exception {
        PlatformAuthTestSupport.seedTeamMember(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword(), teamId, userId);
        try (Connection conn = DriverManager.getConnection(
                        postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
                PreparedStatement ps = conn.prepareStatement(
                        "UPDATE public.team_members SET role = ? WHERE team_id = ? AND user_id = ?")) {
            ps.setString(1, role);
            ps.setLong(2, teamId);
            ps.setLong(3, userId);
            ps.executeUpdate();
        }
    }

    /**
     * Seeds a new user in the fixture's tenant, attaches it to the fixture's team with the given
     * role, and issues a bearer token for it.
     *
     * @param role one of {@link TeamMember}'s {@code ROLE_*} constants
     * @return the raw bearer token for the seeded user
     */
    private String seedTeamMemberWithRoleAndToken(final String role) throws Exception {
        long userId = PlatformAuthTestSupport.seedUser(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword(), tenantId, true);
        seedTeamMemberWithRole(teamId, userId, role);
        return PlatformAuthTestSupport.issueToken(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword(),
                userId, "active", Instant.now().plusSeconds(3600));
    }
}

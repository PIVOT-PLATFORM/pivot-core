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

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration tests for {@link CapacityEventController} exercising the full Spring context
 * against a real PostgreSQL database (E11 — F11.1 events CRUD + F11.3 hierarchy).
 *
 * <p>Covers the create-get-update-delete happy path, listing with filters, children listing,
 * hierarchy depth rejection, invalid date range, cross-tenant isolation, and the write-role gate
 * ({@code MEMBRE} team members may read but not write).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class CapacityEventControllerIT extends AbstractAgiliteIntegrationTest {

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
    // Happy path: create -> get -> update -> delete
    // -------------------------------------------------------------------------

    @Test
    void create_asWriteCapableMember_returns201WithEventDetail() throws Exception {
        mockMvc.perform(
                        post(EVENTS_PATH)
                                .header("Authorization", "Bearer " + responsableToken)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                                        {"teamId":%d,"type":"SPRINT","name":"Sprint 1",
                                         "startDate":"2026-01-05","endDate":"2026-01-16"}
                                        """.formatted(teamId)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").isString())
                .andExpect(jsonPath("$.teamId").value(teamId))
                .andExpect(jsonPath("$.tenantId").value(tenantId))
                .andExpect(jsonPath("$.type").value("SPRINT"))
                .andExpect(jsonPath("$.status").value("PLANNING"))
                .andExpect(jsonPath("$.name").value("Sprint 1"))
                .andExpect(jsonPath("$.startDate").value("2026-01-05"))
                .andExpect(jsonPath("$.endDate").value("2026-01-16"))
                .andExpect(jsonPath("$.workingDays").isArray());
    }

    @Test
    void getUpdateDelete_happyPath() throws Exception {
        String eventId = createEvent(responsableToken, teamId, "SPRINT", "Sprint 2",
                "2026-02-02", "2026-02-13", null);

        mockMvc.perform(
                        get(EVENTS_PATH + "/" + eventId)
                                .header("Authorization", "Bearer " + responsableToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(eventId))
                .andExpect(jsonPath("$.name").value("Sprint 2"));

        mockMvc.perform(
                        put(EVENTS_PATH + "/" + eventId)
                                .header("Authorization", "Bearer " + responsableToken)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                                        {"teamId":%d,"type":"SPRINT","name":"Sprint 2",
                                         "startDate":"2026-02-02","endDate":"2026-02-13",
                                         "status":"ACTIVE","notes":"in progress","focusFactor":0.75}
                                        """.formatted(teamId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.notes").value("in progress"))
                .andExpect(jsonPath("$.focusFactor").value(0.75));

        mockMvc.perform(
                        delete(EVENTS_PATH + "/" + eventId)
                                .header("Authorization", "Bearer " + responsableToken))
                .andExpect(status().isNoContent());

        mockMvc.perform(
                        get(EVENTS_PATH + "/" + eventId)
                                .header("Authorization", "Bearer " + responsableToken))
                .andExpect(status().isNotFound());
    }

    // -------------------------------------------------------------------------
    // GET /capacity/events — listing with filters
    // -------------------------------------------------------------------------

    @Test
    void list_withTypeAndStatusFilters_returnsOnlyMatchingEvents() throws Exception {
        createEvent(responsableToken, teamId, "PI_PLANNING", "PI 1", "2026-01-01", "2026-03-31", null);
        createEvent(responsableToken, teamId, "SPRINT", "Sprint A", "2026-01-05", "2026-01-16", null);
        createEvent(responsableToken, teamId, "RELEASE", "Release 1", "2026-03-01", "2026-03-05", null);

        mockMvc.perform(
                        get(EVENTS_PATH)
                                .header("Authorization", "Bearer " + responsableToken)
                                .param("teamId", String.valueOf(teamId))
                                .param("type", "SPRINT"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].type").value("SPRINT"));

        mockMvc.perform(
                        get(EVENTS_PATH)
                                .header("Authorization", "Bearer " + responsableToken)
                                .param("teamId", String.valueOf(teamId))
                                .param("status", "PLANNING"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(3));
    }

    @Test
    void list_teamCallerIsNotMemberOf_returnsEmptyList() throws Exception {
        long otherTeamId = PlatformAuthTestSupport.seedTeam(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword(),
                tenantId, "Other Team " + UUID.randomUUID());

        mockMvc.perform(
                        get(EVENTS_PATH)
                                .header("Authorization", "Bearer " + responsableToken)
                                .param("teamId", String.valueOf(otherTeamId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    // -------------------------------------------------------------------------
    // GET /capacity/events/{piId}/children — F11.3 hierarchy
    // -------------------------------------------------------------------------

    @Test
    void children_listsDirectChildrenOfPi() throws Exception {
        String piId = createEvent(responsableToken, teamId, "PI_PLANNING", "PI 2026-Q1",
                "2026-01-01", "2026-03-31", null);
        createEvent(responsableToken, teamId, "SPRINT", "Sprint 1", "2026-01-05", "2026-01-16", piId);
        createEvent(responsableToken, teamId, "SPRINT", "Sprint 2", "2026-01-19", "2026-01-30", piId);

        mockMvc.perform(
                        get(EVENTS_PATH + "/" + piId + "/children")
                                .header("Authorization", "Bearer " + responsableToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2));
    }

    @Test
    void create_hierarchyDeeperThanTwoLevels_returns400() throws Exception {
        String piId = createEvent(responsableToken, teamId, "PI_PLANNING", "PI",
                "2026-01-01", "2026-03-31", null);
        String sprintId = createEvent(responsableToken, teamId, "SPRINT", "Sprint",
                "2026-01-05", "2026-01-16", piId);

        mockMvc.perform(
                        post(EVENTS_PATH)
                                .header("Authorization", "Bearer " + responsableToken)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                                        {"teamId":%d,"type":"CUSTOM","name":"Too deep",
                                         "startDate":"2026-01-06","endDate":"2026-01-07","parentId":"%s"}
                                        """.formatted(teamId, sprintId)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("HIERARCHY_TOO_DEEP"));
    }

    @Test
    void create_parentNotPiPlanning_returns400WithInvalidParentType() throws Exception {
        String releaseId = createEvent(responsableToken, teamId, "RELEASE", "Release",
                "2026-01-01", "2026-01-31", null);

        mockMvc.perform(
                        post(EVENTS_PATH)
                                .header("Authorization", "Bearer " + responsableToken)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                                        {"teamId":%d,"type":"SPRINT","name":"Sprint",
                                         "startDate":"2026-01-05","endDate":"2026-01-16","parentId":"%s"}
                                        """.formatted(teamId, releaseId)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PARENT_TYPE"));
    }

    // -------------------------------------------------------------------------
    // Validation
    // -------------------------------------------------------------------------

    @Test
    void create_endDateBeforeStartDate_returns400() throws Exception {
        mockMvc.perform(
                        post(EVENTS_PATH)
                                .header("Authorization", "Bearer " + responsableToken)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                                        {"teamId":%d,"type":"SPRINT","name":"Backwards",
                                         "startDate":"2026-01-16","endDate":"2026-01-05"}
                                        """.formatted(teamId)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_DATE_RANGE"));
    }

    @Test
    void create_focusFactorOutOfRange_returns400() throws Exception {
        mockMvc.perform(
                        post(EVENTS_PATH)
                                .header("Authorization", "Bearer " + responsableToken)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                                        {"teamId":%d,"type":"SPRINT","name":"Bad focus",
                                         "startDate":"2026-01-05","endDate":"2026-01-16","focusFactor":1.5}
                                        """.formatted(teamId)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("FOCUS_OUT_OF_RANGE"));
    }

    // -------------------------------------------------------------------------
    // Tenant isolation
    // -------------------------------------------------------------------------

    /**
     * Given a capacity event belonging to a different tenant, when GET
     * /capacity/events/{id} is called, then it returns 404 — never confirms cross-tenant
     * existence.
     */
    @Test
    void findById_crossTenant_returns404() throws Exception {
        String eventId = createEvent(responsableToken, teamId, "SPRINT", "Cross-tenant Sprint",
                "2026-01-05", "2026-01-16", null);

        mockMvc.perform(
                        get(EVENTS_PATH + "/" + eventId)
                                .header("Authorization", "Bearer " + otherTenantToken))
                .andExpect(status().isNotFound());
    }

    // -------------------------------------------------------------------------
    // Write-role gate
    // -------------------------------------------------------------------------

    /**
     * Given a caller whose team role is {@code MEMBRE} (read-only, the closest equivalent to a
     * VIEWER in this repo's actual role model), when POST /capacity/events is called, then it
     * returns 403.
     */
    @Test
    void create_asReadOnlyMember_returns403() throws Exception {
        String readerToken = seedTeamMemberWithRoleAndToken(TeamMember.ROLE_MEMBRE);

        mockMvc.perform(
                        post(EVENTS_PATH)
                                .header("Authorization", "Bearer " + readerToken)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                                        {"teamId":%d,"type":"SPRINT","name":"Forbidden",
                                         "startDate":"2026-01-05","endDate":"2026-01-16"}
                                        """.formatted(teamId)))
                .andExpect(status().isForbidden());
    }

    /**
     * Given a caller whose team role is {@code MEMBRE}, when GET /capacity/events/{id} is
     * called, then it still returns 200 — reads only require membership, not write privileges.
     */
    @Test
    void findById_asReadOnlyMember_returns200() throws Exception {
        String eventId = createEvent(responsableToken, teamId, "SPRINT", "Readable",
                "2026-01-05", "2026-01-16", null);
        String readerToken = seedTeamMemberWithRoleAndToken(TeamMember.ROLE_MEMBRE);

        mockMvc.perform(
                        get(EVENTS_PATH + "/" + eventId)
                                .header("Authorization", "Bearer " + readerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(eventId));
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /** Creates an event via the API and returns its id. */
    private String createEvent(
            final String token, final long teamId, final String type, final String name,
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

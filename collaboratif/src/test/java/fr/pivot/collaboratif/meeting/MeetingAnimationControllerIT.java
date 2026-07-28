package fr.pivot.collaboratif.meeting;

import com.jayway.jsonpath.JsonPath;
import fr.pivot.collaboratif.AbstractCollaboratifIntegrationTest;
import fr.pivot.collaboratif.testsupport.PlatformAuthTestSupport;
import fr.pivot.collaboratif.testsupport.PlatformAuthTestSupport.AuthFixture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.time.Instant;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration tests for the MeetOps meeting animation REST surface (US12.2.1) exercising the
 * full Spring context against a real PostgreSQL database provided by Testcontainers. Mirrors
 * {@link MeetingControllerIT}'s setup shape and its MockMvc-path-without-context-path caveat.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class MeetingAnimationControllerIT extends AbstractCollaboratifIntegrationTest {

    private static final String MEETINGS_PATH = "/collaboratif/meetings";

    @Autowired
    private WebApplicationContext wac;

    private MockMvc mockMvc;
    private AuthFixture owner;
    private AuthFixture otherTenantUser;
    private AuthFixture sameTenantNonOwner;

    @BeforeEach
    void setUp() throws Exception {
        mockMvc = MockMvcBuilders.webAppContextSetup(wac).build();
        owner = PlatformAuthTestSupport.seedActiveUserWithToken(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        otherTenantUser = PlatformAuthTestSupport.seedActiveUserWithToken(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        // A second user seeded under the OWNER's own tenant (AC-S2 needs a same-tenant,
        // non-owner, non-admin caller) — seedActiveUserWithToken always mints a fresh tenant, so
        // seed the user directly against owner.tenantId() instead.
        long sameTenantUserId = PlatformAuthTestSupport.seedUser(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword(), owner.tenantId(), true);
        String sameTenantToken = PlatformAuthTestSupport.issueToken(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword(), sameTenantUserId,
                "active", Instant.now().plusSeconds(3600));
        sameTenantNonOwner = new AuthFixture(owner.tenantId(), sameTenantUserId, sameTenantToken);
    }

    private String createMeeting(final AuthFixture caller, final String agendaItemsJson) throws Exception {
        MvcResult result = mockMvc.perform(post(MEETINGS_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", caller.authorizationHeader())
                        .content("""
                                {"title":"Sprint Review","scheduledAt":"2026-08-01T10:00:00Z",
                                 "totalDurationMinutes":30,"agendaItems":%s}""".formatted(agendaItemsJson)))
                .andReturn();
        return JsonPath.read(result.getResponse().getContentAsString(), "$.id");
    }

    private String createMeetingWithTwoItems(final AuthFixture caller) throws Exception {
        return createMeeting(caller, """
                [{"title":"Point A","durationMinutes":5,"type":"INFO"},
                 {"title":"Point B","durationMinutes":5,"type":"DISCUSSION"}]""");
    }

    private String createMeetingWithOneItem(final AuthFixture caller) throws Exception {
        return createMeeting(caller, """
                [{"title":"Point A","durationMinutes":5,"type":"INFO"}]""");
    }

    private void start(final AuthFixture caller, final String meetingId) throws Exception {
        mockMvc.perform(post(MEETINGS_PATH + "/" + meetingId + "/start")
                        .header("Authorization", caller.authorizationHeader()))
                .andExpect(status().isOk());
    }

    // -------------------------------------------------------------------------
    // AC-01 — start
    // -------------------------------------------------------------------------

    @Test
    void start_ac01_happyPath_returns200AndLiveStateShowsFirstItemCurrent() throws Exception {
        String meetingId = createMeetingWithTwoItems(owner);

        start(owner, meetingId);

        MvcResult live = mockMvc.perform(get(MEETINGS_PATH + "/" + meetingId + "/live")
                        .header("Authorization", owner.authorizationHeader()))
                .andExpect(status().isOk())
                .andReturn();
        String body = live.getResponse().getContentAsString();
        assertThat((String) JsonPath.read(body, "$.status")).isEqualTo("IN_PROGRESS");
        assertThat((Integer) JsonPath.read(body, "$.currentIndex")).isZero();
        assertThat((Integer) JsonPath.read(body, "$.totalItems")).isEqualTo(2);
    }

    @Test
    void start_ac_e1_whenAlreadyInProgress_returns409() throws Exception {
        String meetingId = createMeetingWithOneItem(owner);
        start(owner, meetingId);

        mockMvc.perform(post(MEETINGS_PATH + "/" + meetingId + "/start")
                        .header("Authorization", owner.authorizationHeader()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("MEETING_ALREADY_STARTED"));
    }

    @Test
    void start_ac_e1_whenAlreadyEnded_returns409() throws Exception {
        String meetingId = createMeetingWithOneItem(owner);
        start(owner, meetingId);
        mockMvc.perform(post(MEETINGS_PATH + "/" + meetingId + "/end")
                        .header("Authorization", owner.authorizationHeader()))
                .andExpect(status().isOk());

        mockMvc.perform(post(MEETINGS_PATH + "/" + meetingId + "/start")
                        .header("Authorization", owner.authorizationHeader()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("MEETING_ALREADY_ENDED"));
    }

    @Test
    void start_ac_e3_whenNoAgendaItems_returns409() throws Exception {
        String meetingId = createMeeting(owner, "[]");

        mockMvc.perform(post(MEETINGS_PATH + "/" + meetingId + "/start")
                        .header("Authorization", owner.authorizationHeader()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("MEETING_HAS_NO_AGENDA"));
    }

    @Test
    void start_ac_s1_crossTenantMeeting_returns404() throws Exception {
        String meetingId = createMeetingWithOneItem(owner);

        mockMvc.perform(post(MEETINGS_PATH + "/" + meetingId + "/start")
                        .header("Authorization", otherTenantUser.authorizationHeader()))
                .andExpect(status().isNotFound());
    }

    @Test
    void start_ac_s2_sameTenantNonOwnerNonAdmin_returns403() throws Exception {
        String meetingId = createMeetingWithOneItem(owner);

        mockMvc.perform(post(MEETINGS_PATH + "/" + meetingId + "/start")
                        .header("Authorization", sameTenantNonOwner.authorizationHeader()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("MEETING_FACILITATOR_ONLY"));
    }

    @Test
    void start_withoutBearerToken_returns401() throws Exception {
        String meetingId = createMeetingWithOneItem(owner);

        mockMvc.perform(post(MEETINGS_PATH + "/" + meetingId + "/start"))
                .andExpect(status().isUnauthorized());
    }

    // -------------------------------------------------------------------------
    // AC-03 — agenda/next
    // -------------------------------------------------------------------------

    @Test
    void next_ac03_advancesToTheNextItem() throws Exception {
        String meetingId = createMeetingWithTwoItems(owner);
        start(owner, meetingId);

        MvcResult result = mockMvc.perform(post(MEETINGS_PATH + "/" + meetingId + "/agenda/next")
                        .header("Authorization", owner.authorizationHeader()))
                .andExpect(status().isOk())
                .andReturn();
        String body = result.getResponse().getContentAsString();
        assertThat((String) JsonPath.read(body, "$.status")).isEqualTo("IN_PROGRESS");
        assertThat((Integer) JsonPath.read(body, "$.currentIndex")).isEqualTo(1);
    }

    @Test
    void next_ac03_gate1_onTheLastItem_endsTheMeeting() throws Exception {
        String meetingId = createMeetingWithOneItem(owner);
        start(owner, meetingId);

        MvcResult result = mockMvc.perform(post(MEETINGS_PATH + "/" + meetingId + "/agenda/next")
                        .header("Authorization", owner.authorizationHeader()))
                .andExpect(status().isOk())
                .andReturn();
        assertThat((String) JsonPath.read(result.getResponse().getContentAsString(), "$.status")).isEqualTo("ENDED");
    }

    @Test
    void next_ac_e2_whenMeetingNotInProgress_returns409() throws Exception {
        String meetingId = createMeetingWithTwoItems(owner);

        mockMvc.perform(post(MEETINGS_PATH + "/" + meetingId + "/agenda/next")
                        .header("Authorization", owner.authorizationHeader()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("MEETING_NOT_IN_PROGRESS"));
    }

    // -------------------------------------------------------------------------
    // AC-06 — end
    // -------------------------------------------------------------------------

    @Test
    void end_ac06_happyPath_returns200AndLiveStateShowsEnded() throws Exception {
        String meetingId = createMeetingWithTwoItems(owner);
        start(owner, meetingId);

        MvcResult result = mockMvc.perform(post(MEETINGS_PATH + "/" + meetingId + "/end")
                        .header("Authorization", owner.authorizationHeader()))
                .andExpect(status().isOk())
                .andReturn();
        assertThat((String) JsonPath.read(result.getResponse().getContentAsString(), "$.status")).isEqualTo("ENDED");
    }

    @Test
    void end_ac_e2_whenMeetingNotInProgress_returns409() throws Exception {
        String meetingId = createMeetingWithOneItem(owner);

        mockMvc.perform(post(MEETINGS_PATH + "/" + meetingId + "/end")
                        .header("Authorization", owner.authorizationHeader()))
                .andExpect(status().isConflict());
    }

    // -------------------------------------------------------------------------
    // AC-08 / AC-E4 — actions
    // -------------------------------------------------------------------------

    @Test
    void addAction_ac08_happyPath_returns201WithCreatedAction() throws Exception {
        String meetingId = createMeetingWithOneItem(owner);
        start(owner, meetingId);

        MvcResult result = mockMvc.perform(post(MEETINGS_PATH + "/" + meetingId + "/actions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", owner.authorizationHeader())
                        .content("""
                                {"label":"Follow up with legal","ownerUserId":%d,"dueDate":"%s"}"""
                                .formatted(owner.userId(), LocalDate.now().plusDays(7))))
                .andExpect(status().isCreated())
                .andReturn();
        String body = result.getResponse().getContentAsString();
        assertThat((String) JsonPath.read(body, "$.label")).isEqualTo("Follow up with legal");
        assertThat((String) JsonPath.read(body, "$.status")).isEqualTo("OPEN");
    }

    @Test
    void addAction_ac_e4_withBlankLabel_returns400() throws Exception {
        String meetingId = createMeetingWithOneItem(owner);
        start(owner, meetingId);

        mockMvc.perform(post(MEETINGS_PATH + "/" + meetingId + "/actions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", owner.authorizationHeader())
                        .content("""
                                {"label":""}"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_LABEL"));
    }

    @Test
    void addAction_ac_e4_withPastDueDate_returns400() throws Exception {
        String meetingId = createMeetingWithOneItem(owner);
        start(owner, meetingId);

        mockMvc.perform(post(MEETINGS_PATH + "/" + meetingId + "/actions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", owner.authorizationHeader())
                        .content("""
                                {"label":"Valid label","dueDate":"%s"}""".formatted(LocalDate.now().minusDays(1))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_DUE_DATE"));
    }

    @Test
    void addAction_ac_e2_whenMeetingNotInProgress_returns409() throws Exception {
        String meetingId = createMeetingWithOneItem(owner);

        mockMvc.perform(post(MEETINGS_PATH + "/" + meetingId + "/actions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", owner.authorizationHeader())
                        .content("""
                                {"label":"Valid label"}"""))
                .andExpect(status().isConflict());
    }

    // -------------------------------------------------------------------------
    // AC-07 — live / resynchronisation
    // -------------------------------------------------------------------------

    @Test
    void live_ac07_beforeStart_showsDraftStatusAndNoCurrentItem() throws Exception {
        String meetingId = createMeetingWithOneItem(owner);

        MvcResult result = mockMvc.perform(get(MEETINGS_PATH + "/" + meetingId + "/live")
                        .header("Authorization", owner.authorizationHeader()))
                .andExpect(status().isOk())
                .andReturn();
        String body = result.getResponse().getContentAsString();
        assertThat((String) JsonPath.read(body, "$.status")).isEqualTo("DRAFT");
        assertThat(body).doesNotContain("\"currentIndex\"");
    }

    @Test
    void live_ac_s1_crossTenant_returns404() throws Exception {
        String meetingId = createMeetingWithOneItem(owner);

        mockMvc.perform(get(MEETINGS_PATH + "/" + meetingId + "/live")
                        .header("Authorization", otherTenantUser.authorizationHeader()))
                .andExpect(status().isNotFound());
    }
}

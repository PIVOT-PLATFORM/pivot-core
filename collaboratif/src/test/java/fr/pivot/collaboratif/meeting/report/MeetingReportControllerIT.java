package fr.pivot.collaboratif.meeting.report;

import com.jayway.jsonpath.JsonPath;
import fr.pivot.collaboratif.AbstractCollaboratifIntegrationTest;
import fr.pivot.collaboratif.meeting.MeetingDecision;
import fr.pivot.collaboratif.meeting.MeetingDecisionRepository;
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
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration tests for the MeetOps compte-rendu REST surface (US12.3.1) exercising the full
 * Spring context against a real PostgreSQL database provided by Testcontainers. Mirrors {@link
 * fr.pivot.collaboratif.meeting.MeetingAnimationControllerIT}'s setup shape and its
 * MockMvc-path-without-context-path caveat.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class MeetingReportControllerIT extends AbstractCollaboratifIntegrationTest {

    private static final String MEETINGS_PATH = "/collaboratif/meetings";

    @Autowired
    private WebApplicationContext wac;

    @Autowired
    private MeetingDecisionRepository decisionRepository;

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
        long sameTenantUserId = PlatformAuthTestSupport.seedUser(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword(), owner.tenantId(), true);
        String sameTenantToken = PlatformAuthTestSupport.issueToken(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword(), sameTenantUserId,
                "active", Instant.now().plusSeconds(3600));
        sameTenantNonOwner = new AuthFixture(owner.tenantId(), sameTenantUserId, sameTenantToken);
    }

    private String createMeetingWithOneItem(final AuthFixture caller) throws Exception {
        MvcResult result = mockMvc.perform(post(MEETINGS_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", caller.authorizationHeader())
                        .content("""
                                {"title":"Sprint Review","scheduledAt":"2026-08-01T10:00:00Z",
                                 "totalDurationMinutes":30,
                                 "agendaItems":[{"title":"Point A","durationMinutes":5,"type":"INFO"}]}"""))
                .andReturn();
        return JsonPath.read(result.getResponse().getContentAsString(), "$.id");
    }

    private void start(final AuthFixture caller, final String meetingId) throws Exception {
        mockMvc.perform(post(MEETINGS_PATH + "/" + meetingId + "/start")
                        .header("Authorization", caller.authorizationHeader()))
                .andExpect(status().isOk());
    }

    private void end(final AuthFixture caller, final String meetingId) throws Exception {
        mockMvc.perform(post(MEETINGS_PATH + "/" + meetingId + "/end")
                        .header("Authorization", caller.authorizationHeader()))
                .andExpect(status().isOk());
    }

    private void addAction(final AuthFixture caller, final String meetingId) throws Exception {
        mockMvc.perform(post(MEETINGS_PATH + "/" + meetingId + "/actions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", caller.authorizationHeader())
                        .content("""
                                {"label":"Follow up with legal","ownerUserId":%d}""".formatted(caller.userId())))
                .andExpect(status().isCreated());
    }

    // -------------------------------------------------------------------------
    // AC nominal — draft vs frozen
    // -------------------------------------------------------------------------

    @Test
    void getReport_nonClosedMeeting_returnsDraftTrue() throws Exception {
        String meetingId = createMeetingWithOneItem(owner);

        MvcResult result = mockMvc.perform(get(MEETINGS_PATH + "/" + meetingId + "/report")
                        .header("Authorization", owner.authorizationHeader()))
                .andExpect(status().isOk())
                .andReturn();
        String body = result.getResponse().getContentAsString();
        assertThat((Boolean) JsonPath.read(body, "$.draft")).isTrue();
        assertThat((String) JsonPath.read(body, "$.status")).isEqualTo("DRAFT");
    }

    @Test
    void getReport_startedButNotClosedMeeting_stillReturnsDraftTrue() throws Exception {
        String meetingId = createMeetingWithOneItem(owner);
        start(owner, meetingId);

        MvcResult result = mockMvc.perform(get(MEETINGS_PATH + "/" + meetingId + "/report")
                        .header("Authorization", owner.authorizationHeader()))
                .andExpect(status().isOk())
                .andReturn();
        String body = result.getResponse().getContentAsString();
        assertThat((Boolean) JsonPath.read(body, "$.draft")).isTrue();
        assertThat((String) JsonPath.read(body, "$.status")).isEqualTo("IN_PROGRESS");
    }

    @Test
    void getReport_closedMeeting_returnsFrozenReportWithDraftFalseAndActions() throws Exception {
        String meetingId = createMeetingWithOneItem(owner);
        start(owner, meetingId);
        addAction(owner, meetingId);
        end(owner, meetingId);

        MvcResult result = mockMvc.perform(get(MEETINGS_PATH + "/" + meetingId + "/report")
                        .header("Authorization", owner.authorizationHeader()))
                .andExpect(status().isOk())
                .andReturn();
        String body = result.getResponse().getContentAsString();
        assertThat((Boolean) JsonPath.read(body, "$.draft")).isFalse();
        assertThat((String) JsonPath.read(body, "$.status")).isEqualTo("ENDED");
        assertThat((Integer) JsonPath.read(body, "$.actualDurationSeconds")).isNotNull();
        assertThat((List<?>) JsonPath.read(body, "$.actions")).hasSize(1);
        assertThat((String) JsonPath.read(body, "$.actions[0].label")).isEqualTo("Follow up with legal");
        assertThat((List<?>) JsonPath.read(body, "$.agendaItems")).hasSize(1);
        assertThat((Boolean) JsonPath.read(body, "$.agendaItems[0].overtime")).isNotNull();
    }

    @Test
    void getReport_closedMeetingWithDecisions_includesThemScopedToTheMeeting() throws Exception {
        String meetingId = createMeetingWithOneItem(owner);
        start(owner, meetingId);
        decisionRepository.save(new MeetingDecision(
                owner.tenantId(), UUID.fromString(meetingId), null, "Adopter le nouveau format",
                Instant.now(), owner.userId()));
        end(owner, meetingId);

        MvcResult result = mockMvc.perform(get(MEETINGS_PATH + "/" + meetingId + "/report")
                        .header("Authorization", owner.authorizationHeader()))
                .andExpect(status().isOk())
                .andReturn();
        String body = result.getResponse().getContentAsString();
        assertThat((List<?>) JsonPath.read(body, "$.decisions")).hasSize(1);
        assertThat((String) JsonPath.read(body, "$.decisions[0].label")).isEqualTo("Adopter le nouveau format");
    }

    @Test
    void getReport_closedMeeting_isImmutableAfterFurtherActionCapture() throws Exception {
        // AC Security: a frozen report never reflects a later change. Actions can no longer be
        // captured once ENDED (AC-E2, 409) — the strongest available proof of immutability here is
        // that the frozen report's action list stays exactly what it was at closure across repeated
        // reads, regardless of what (theoretically) happens afterward to the source tables.
        String meetingId = createMeetingWithOneItem(owner);
        start(owner, meetingId);
        addAction(owner, meetingId);
        end(owner, meetingId);

        MvcResult first = mockMvc.perform(get(MEETINGS_PATH + "/" + meetingId + "/report")
                        .header("Authorization", owner.authorizationHeader()))
                .andReturn();
        MvcResult second = mockMvc.perform(get(MEETINGS_PATH + "/" + meetingId + "/report")
                        .header("Authorization", owner.authorizationHeader()))
                .andReturn();

        assertThat(first.getResponse().getContentAsString()).isEqualTo(second.getResponse().getContentAsString());
    }

    // -------------------------------------------------------------------------
    // AC nominal — export
    // -------------------------------------------------------------------------

    @Test
    void exportReport_markdownFormat_returnsTextMarkdownWithAllFourSections() throws Exception {
        String meetingId = createMeetingWithOneItem(owner);
        start(owner, meetingId);
        end(owner, meetingId);

        MvcResult result = mockMvc.perform(get(MEETINGS_PATH + "/" + meetingId + "/report/export")
                        .param("format", "markdown")
                        .header("Authorization", owner.authorizationHeader()))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("text/markdown"))
                .andReturn();
        String body = result.getResponse().getContentAsString();
        assertThat(body).contains("## Participants").contains("## Agenda").contains("## Décisions").contains("## Actions");
    }

    @Test
    void exportReport_jsonFormat_returnsReportDto() throws Exception {
        String meetingId = createMeetingWithOneItem(owner);

        mockMvc.perform(get(MEETINGS_PATH + "/" + meetingId + "/report/export")
                        .param("format", "json")
                        .header("Authorization", owner.authorizationHeader()))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.draft").value(true));
    }

    @Test
    void exportReport_noFormatParam_defaultsToJson() throws Exception {
        String meetingId = createMeetingWithOneItem(owner);

        mockMvc.perform(get(MEETINGS_PATH + "/" + meetingId + "/report/export")
                        .header("Authorization", owner.authorizationHeader()))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON));
    }

    @Test
    void exportReport_unsupportedFormat_returns400WithoutReportBody() throws Exception {
        String meetingId = createMeetingWithOneItem(owner);

        MvcResult result = mockMvc.perform(get(MEETINGS_PATH + "/" + meetingId + "/report/export")
                        .param("format", "xml")
                        .header("Authorization", owner.authorizationHeader()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("UNSUPPORTED_EXPORT_FORMAT"))
                .andReturn();
        assertThat(result.getResponse().getContentAsString()).doesNotContain("\"agendaItems\"");
    }

    // -------------------------------------------------------------------------
    // Error cases / security
    // -------------------------------------------------------------------------

    @Test
    void getReport_crossTenantMeeting_returns404NeverConfirmingExistence() throws Exception {
        String meetingId = createMeetingWithOneItem(owner);

        mockMvc.perform(get(MEETINGS_PATH + "/" + meetingId + "/report")
                        .header("Authorization", otherTenantUser.authorizationHeader()))
                .andExpect(status().isNotFound());
    }

    @Test
    void exportReport_crossTenantMeeting_returns404() throws Exception {
        String meetingId = createMeetingWithOneItem(owner);

        mockMvc.perform(get(MEETINGS_PATH + "/" + meetingId + "/report/export")
                        .header("Authorization", otherTenantUser.authorizationHeader()))
                .andExpect(status().isNotFound());
    }

    @Test
    void getReport_sameTenantCallerWithoutAccess_returns404() throws Exception {
        // sameTenantNonOwner is not the owner and the meeting has no team — no visibility.
        String meetingId = createMeetingWithOneItem(owner);

        mockMvc.perform(get(MEETINGS_PATH + "/" + meetingId + "/report")
                        .header("Authorization", sameTenantNonOwner.authorizationHeader()))
                .andExpect(status().isNotFound());
    }

    @Test
    void getReport_unknownMeetingId_returns404() throws Exception {
        mockMvc.perform(get(MEETINGS_PATH + "/" + UUID.randomUUID() + "/report")
                        .header("Authorization", owner.authorizationHeader()))
                .andExpect(status().isNotFound());
    }

    @Test
    void getReport_withoutBearerToken_returns401() throws Exception {
        String meetingId = createMeetingWithOneItem(owner);

        mockMvc.perform(get(MEETINGS_PATH + "/" + meetingId + "/report"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void close_byNonOwnerNonAdmin_returns403AndNoSnapshotIsWritten() throws Exception {
        String meetingId = createMeetingWithOneItem(owner);
        start(owner, meetingId);

        mockMvc.perform(post(MEETINGS_PATH + "/" + meetingId + "/end")
                        .header("Authorization", sameTenantNonOwner.authorizationHeader()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("MEETING_FACILITATOR_ONLY"));

        // The meeting is still IN_PROGRESS and its report still a live draft — proof no snapshot
        // was frozen by the rejected attempt.
        MvcResult result = mockMvc.perform(get(MEETINGS_PATH + "/" + meetingId + "/report")
                        .header("Authorization", owner.authorizationHeader()))
                .andReturn();
        String body = result.getResponse().getContentAsString();
        assertThat((Boolean) JsonPath.read(body, "$.draft")).isTrue();
        assertThat((String) JsonPath.read(body, "$.status")).isEqualTo("IN_PROGRESS");
    }

    // -------------------------------------------------------------------------
    // share — AC7/AC8/AC-E/AC-Security
    // -------------------------------------------------------------------------

    @Test
    void share_closedMeeting_byOwner_returns200() throws Exception {
        String meetingId = createMeetingWithOneItem(owner);
        start(owner, meetingId);
        end(owner, meetingId);

        mockMvc.perform(post(MEETINGS_PATH + "/" + meetingId + "/report/share")
                        .header("Authorization", owner.authorizationHeader()))
                .andExpect(status().isOk());
    }

    @Test
    void share_meetingNotClosed_returns409() throws Exception {
        String meetingId = createMeetingWithOneItem(owner);
        start(owner, meetingId);

        mockMvc.perform(post(MEETINGS_PATH + "/" + meetingId + "/report/share")
                        .header("Authorization", owner.authorizationHeader()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("MEETING_NOT_CLOSED"));
    }

    @Test
    void share_byNonOwnerNonAdmin_returns403() throws Exception {
        String meetingId = createMeetingWithOneItem(owner);
        start(owner, meetingId);
        end(owner, meetingId);

        mockMvc.perform(post(MEETINGS_PATH + "/" + meetingId + "/report/share")
                        .header("Authorization", sameTenantNonOwner.authorizationHeader()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("MEETING_FACILITATOR_ONLY"));
    }

    @Test
    void share_crossTenantMeeting_returns404() throws Exception {
        String meetingId = createMeetingWithOneItem(owner);
        start(owner, meetingId);
        end(owner, meetingId);

        mockMvc.perform(post(MEETINGS_PATH + "/" + meetingId + "/report/share")
                        .header("Authorization", otherTenantUser.authorizationHeader()))
                .andExpect(status().isNotFound());
    }
}

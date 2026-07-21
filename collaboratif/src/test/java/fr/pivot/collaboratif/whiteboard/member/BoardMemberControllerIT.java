package fr.pivot.collaboratif.whiteboard.member;

import fr.pivot.collaboratif.AbstractCollaboratifIntegrationTest;
import fr.pivot.collaboratif.testsupport.PlatformAuthTestSupport;
import fr.pivot.collaboratif.testsupport.PlatformAuthTestSupport.AuthFixture;
import fr.pivot.collaboratif.whiteboard.board.BoardMember;
import fr.pivot.collaboratif.whiteboard.board.BoardMemberId;
import fr.pivot.collaboratif.whiteboard.board.BoardMemberRepository;
import fr.pivot.collaboratif.whiteboard.board.BoardRole;
import fr.pivot.collaboratif.whiteboard.member.event.BoardMembershipNotificationRequestedEvent;
import fr.pivot.collaboratif.whiteboard.member.event.BoardMembershipNotificationRequestedEvent.Kind;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration tests for {@link BoardMemberController} exercising the full Spring context
 * against a real PostgreSQL database and Redis provided by Testcontainers.
 *
 * <p>Covers US08.2.3 acceptance criteria: listing members (GET), changing roles (PATCH),
 * and removing members (DELETE); and US08.2.5 (named e-mail invitations, POST), including
 * OWNER-only access control, 404/403/400 cases, and the {@link BoardMembershipNotificationRequestedEvent}
 * side effects this module publishes towards the shared notification system (verified here as
 * published events, not as persisted {@code fr.pivot.notification} rows — this module cannot
 * depend on that package, see the event's own Javadoc).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@RecordApplicationEvents
class BoardMemberControllerIT extends AbstractCollaboratifIntegrationTest {

    private static final String BOARDS_PATH = "/collaboratif/whiteboard/boards";

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private BoardMemberRepository boardMemberRepository;

    @Autowired
    private ApplicationEvents events;

    private MockMvc mockMvc;
    private final ObjectMapper mapper = new ObjectMapper();

    private long ownerId;
    private long tenantId;
    private String ownerToken;
    private long editorId;
    private String editorToken;
    private String editorEmail;
    private UUID boardId;

    /**
     * Seeds a real tenant/owner/token fixture plus a second user (EDITOR) in the same tenant
     * via {@link PlatformAuthTestSupport} (EN08.3), creates a fresh board owned by that user,
     * and adds the second user as an EDITOR member before each test.
     */
    @BeforeEach
    void setUp() throws Exception {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).build();

        AuthFixture ownerFixture = PlatformAuthTestSupport.seedActiveUserWithToken(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        tenantId = ownerFixture.tenantId();
        ownerId = ownerFixture.userId();
        ownerToken = ownerFixture.rawToken();

        editorEmail = "editor-" + UUID.randomUUID() + "@pivot.invalid";
        editorId = PlatformAuthTestSupport.seedUserWithName(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword(),
                tenantId, editorEmail, "Marie", "Dupont");
        editorToken = PlatformAuthTestSupport.issueToken(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword(),
                editorId, "active", Instant.now().plusSeconds(3600));

        String createResponse = mockMvc.perform(post(BOARDS_PATH)
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\": \"Member Test Board\"}"))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse().getContentAsString();

        JsonNode boardJson = mapper.readTree(createResponse);
        boardId = UUID.fromString(boardJson.get("id").asText());

        boardMemberRepository.save(
                new BoardMember(
                        new BoardMemberId(boardId, editorId),
                        BoardRole.EDITOR,
                        Instant.now()));
    }

    // -------------------------------------------------------------------------
    // GET /whiteboard/boards/{boardId}/members — list members
    // -------------------------------------------------------------------------

    @Test
    void listMembers_asOwner_returnsBothMembers() throws Exception {
        mockMvc.perform(get(BOARDS_PATH + "/" + boardId + "/members")
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[0].role").value("OWNER"))
                .andExpect(jsonPath("$[1].userId").value(editorId))
                .andExpect(jsonPath("$[1].role").value("EDITOR"))
                .andExpect(jsonPath("$[1].email").value(editorEmail))
                .andExpect(jsonPath("$[1].firstName").value("Marie"))
                .andExpect(jsonPath("$[1].lastName").value("Dupont"));
    }

    @Test
    void listMembers_memberFromAnotherTenant_returnsNullIdentity() throws Exception {
        // FK board_member.user_id -> public.users : l'utilisateur DOIT exister, mais dans un
        // autre tenant. La résolution d'annuaire est tenant-scopée => identité non résolue (null),
        // exactement le cas « compte hors tenant » du design.
        long otherTenantId = PlatformAuthTestSupport.seedTenant(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword(), null);
        long foreignUserId = PlatformAuthTestSupport.seedUserWithName(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword(),
                otherTenantId, "foreign-" + UUID.randomUUID() + "@pivot.invalid", "Ghost", "User");
        boardMemberRepository.save(new BoardMember(
                new BoardMemberId(boardId, foreignUserId), BoardRole.VIEWER, Instant.now()));

        String body = mockMvc.perform(get(BOARDS_PATH + "/" + boardId + "/members")
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(3)))
                .andReturn()
                .getResponse().getContentAsString();

        JsonNode foreign = null;
        for (JsonNode node : mapper.readTree(body)) {
            if (node.get("userId").asLong() == foreignUserId) {
                foreign = node;
                break;
            }
        }
        assertThat(foreign).as("foreign-tenant member present in list").isNotNull();
        assertThat(foreign.get("role").asText()).isEqualTo("VIEWER");
        assertThat(foreign.get("email").isNull()).isTrue();
        assertThat(foreign.get("firstName").isNull()).isTrue();
        assertThat(foreign.get("lastName").isNull()).isTrue();
    }

    @Test
    void listMembers_asMember_returns200() throws Exception {
        mockMvc.perform(get(BOARDS_PATH + "/" + boardId + "/members")
                        .header("Authorization", "Bearer " + editorToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)));
    }

    @Test
    void listMembers_nonMember_returns404() throws Exception {
        long strangerId = PlatformAuthTestSupport.seedUser(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword(), tenantId, true);
        String strangerToken = PlatformAuthTestSupport.issueToken(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword(),
                strangerId, "active", Instant.now().plusSeconds(3600));

        mockMvc.perform(get(BOARDS_PATH + "/" + boardId + "/members")
                        .header("Authorization", "Bearer " + strangerToken))
                .andExpect(status().isNotFound());
    }

    @Test
    void listMembers_crossTenant_returns404() throws Exception {
        AuthFixture otherTenantFixture = PlatformAuthTestSupport.seedActiveUserWithToken(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());

        mockMvc.perform(get(BOARDS_PATH + "/" + boardId + "/members")
                        .header("Authorization", "Bearer " + otherTenantFixture.rawToken()))
                .andExpect(status().isNotFound());
    }

    @Test
    void listMembers_missingAuth_returns401() throws Exception {
        mockMvc.perform(get(BOARDS_PATH + "/" + boardId + "/members"))
                .andExpect(status().isUnauthorized());
    }

    // -------------------------------------------------------------------------
    // PATCH /whiteboard/boards/{boardId}/members/{userId}/role — update role
    // -------------------------------------------------------------------------

    @Test
    void updateRole_ownerChangesEditorToViewer_returns200() throws Exception {
        mockMvc.perform(patch(BOARDS_PATH + "/" + boardId + "/members/" + editorId + "/role")
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"role\": \"VIEWER\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value(editorId))
                .andExpect(jsonPath("$.role").value("VIEWER"));
    }

    @Test
    void updateRole_nonOwner_returns403() throws Exception {
        mockMvc.perform(patch(BOARDS_PATH + "/" + boardId + "/members/" + editorId + "/role")
                        .header("Authorization", "Bearer " + editorToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"role\": \"VIEWER\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void updateRole_targetIsOwner_returns400() throws Exception {
        mockMvc.perform(patch(BOARDS_PATH + "/" + boardId + "/members/" + ownerId + "/role")
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"role\": \"EDITOR\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void updateRole_toOwnerRole_returns400() throws Exception {
        mockMvc.perform(patch(BOARDS_PATH + "/" + boardId + "/members/" + editorId + "/role")
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"role\": \"OWNER\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void updateRole_nullRole_returns400() throws Exception {
        mockMvc.perform(patch(BOARDS_PATH + "/" + boardId + "/members/" + editorId + "/role")
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"role\": null}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void updateRole_memberNotFound_returns404() throws Exception {
        long unknown = 999_999_999L;
        mockMvc.perform(patch(BOARDS_PATH + "/" + boardId + "/members/" + unknown + "/role")
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"role\": \"VIEWER\"}"))
                .andExpect(status().isNotFound());
    }

    // -------------------------------------------------------------------------
    // DELETE /whiteboard/boards/{boardId}/members/{userId} — remove member
    // -------------------------------------------------------------------------

    @Test
    void removeMember_ownerRemovesEditor_returns204() throws Exception {
        mockMvc.perform(delete(BOARDS_PATH + "/" + boardId + "/members/" + editorId)
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isNoContent());
    }

    @Test
    void removeMember_nonOwner_returns403() throws Exception {
        mockMvc.perform(delete(BOARDS_PATH + "/" + boardId + "/members/" + editorId)
                        .header("Authorization", "Bearer " + editorToken))
                .andExpect(status().isForbidden());
    }

    @Test
    void removeMember_targetIsOwner_returns400() throws Exception {
        mockMvc.perform(delete(BOARDS_PATH + "/" + boardId + "/members/" + ownerId)
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isBadRequest());
    }

    @Test
    void removeMember_memberNotFound_returns404() throws Exception {
        long unknown = 999_999_999L;
        mockMvc.perform(delete(BOARDS_PATH + "/" + boardId + "/members/" + unknown)
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isNotFound());
    }

    @Test
    void removeMember_emitsBoardAccessRevokedNotification() throws Exception {
        mockMvc.perform(delete(BOARDS_PATH + "/" + boardId + "/members/" + editorId)
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isNoContent());

        assertThat(notificationsFor(editorId, Kind.ACCESS_REVOKED)).hasSize(1);
    }

    @Test
    void updateRole_emitsBoardRoleChangedNotification() throws Exception {
        mockMvc.perform(patch(BOARDS_PATH + "/" + boardId + "/members/" + editorId + "/role")
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"role\": \"VIEWER\"}"))
                .andExpect(status().isOk());

        assertThat(notificationsFor(editorId, Kind.ROLE_CHANGED)).hasSize(1);
    }

    // -------------------------------------------------------------------------
    // POST /whiteboard/boards/{boardId}/members — invite by e-mail (US08.2.5)
    // -------------------------------------------------------------------------

    @Test
    void invite_ownerInvitesNewUser_returns201WithDefaultViewerRole() throws Exception {
        String email = "invitee-" + UUID.randomUUID() + "@pivot.invalid";
        long inviteeId = PlatformAuthTestSupport.seedUserWithEmail(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword(),
                tenantId, email, true);

        mockMvc.perform(post(BOARDS_PATH + "/" + boardId + "/members")
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\": \"" + email + "\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.userId").value(inviteeId))
                .andExpect(jsonPath("$.role").value("VIEWER"))
                .andExpect(jsonPath("$.email").value(email));

        assertThat(notificationsFor(inviteeId, Kind.SHARED)).hasSize(1);
    }

    @Test
    void invite_ownerInvitesWithExplicitRole_returns201WithThatRole() throws Exception {
        String email = "invitee-" + UUID.randomUUID() + "@pivot.invalid";
        long inviteeId = PlatformAuthTestSupport.seedUserWithEmail(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword(),
                tenantId, email, true);

        mockMvc.perform(post(BOARDS_PATH + "/" + boardId + "/members")
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\": \"" + email + "\", \"role\": \"EDITOR\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.role").value("EDITOR"));
    }

    @Test
    void invite_reInvitingExistingMemberWithDifferentRole_updatesRoleAndNotifies() throws Exception {
        mockMvc.perform(post(BOARDS_PATH + "/" + boardId + "/members")
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(inviteBody(editorEmail, "VIEWER")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.userId").value(editorId))
                .andExpect(jsonPath("$.role").value("VIEWER"));

        assertThat(notificationsFor(editorId, Kind.ROLE_CHANGED)).hasSize(1);
        assertThat(boardMemberRepository.findByIdBoardIdAndIdUserId(boardId, editorId))
                .isPresent()
                .get()
                .extracting(BoardMember::getRole)
                .isEqualTo(BoardRole.VIEWER);
    }

    @Test
    void invite_reInvitingExistingMemberWithSameRole_isNoopWithoutNotification() throws Exception {
        mockMvc.perform(post(BOARDS_PATH + "/" + boardId + "/members")
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(inviteBody(editorEmail, "EDITOR")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.role").value("EDITOR"));

        assertThat(notificationsFor(editorId, Kind.ROLE_CHANGED)).isEmpty();
        assertThat(notificationsFor(editorId, Kind.SHARED)).isEmpty();
    }

    @Test
    void invite_nonOwner_returns403() throws Exception {
        String email = "invitee-" + UUID.randomUUID() + "@pivot.invalid";
        PlatformAuthTestSupport.seedUserWithEmail(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword(),
                tenantId, email, true);

        mockMvc.perform(post(BOARDS_PATH + "/" + boardId + "/members")
                        .header("Authorization", "Bearer " + editorToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(inviteBody(email, null)))
                .andExpect(status().isForbidden());
    }

    @Test
    void invite_unknownEmail_returns404() throws Exception {
        mockMvc.perform(post(BOARDS_PATH + "/" + boardId + "/members")
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(inviteBody("nobody-" + UUID.randomUUID() + "@pivot.invalid", null)))
                .andExpect(status().isNotFound());
    }

    @Test
    void invite_deactivatedAccountEmail_returns404() throws Exception {
        String email = "inactive-" + UUID.randomUUID() + "@pivot.invalid";
        PlatformAuthTestSupport.seedUserWithEmail(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword(),
                tenantId, email, false);

        mockMvc.perform(post(BOARDS_PATH + "/" + boardId + "/members")
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(inviteBody(email, null)))
                .andExpect(status().isNotFound());
    }

    @Test
    void invite_selfInvite_returns400() throws Exception {
        String ownerEmail = "owner-" + UUID.randomUUID() + "@pivot.invalid";
        long selfOwnerId = PlatformAuthTestSupport.seedUserWithEmail(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword(),
                tenantId, ownerEmail, true);
        String selfOwnerToken = PlatformAuthTestSupport.issueToken(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword(),
                selfOwnerId, "active", Instant.now().plusSeconds(3600));
        UUID selfBoardId = createBoard(selfOwnerToken, "Self Invite Board");

        mockMvc.perform(post(BOARDS_PATH + "/" + selfBoardId + "/members")
                        .header("Authorization", "Bearer " + selfOwnerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(inviteBody(ownerEmail, null)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("SELF_INVITE"));
    }

    @Test
    void invite_roleOwner_returns400() throws Exception {
        String email = "invitee-" + UUID.randomUUID() + "@pivot.invalid";
        PlatformAuthTestSupport.seedUserWithEmail(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword(),
                tenantId, email, true);

        mockMvc.perform(post(BOARDS_PATH + "/" + boardId + "/members")
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(inviteBody(email, "OWNER")))
                .andExpect(status().isBadRequest());
    }

    @Test
    void invite_invalidEmail_returns400() throws Exception {
        mockMvc.perform(post(BOARDS_PATH + "/" + boardId + "/members")
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\": \"not-an-email\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void invite_missingAuth_returns401() throws Exception {
        mockMvc.perform(post(BOARDS_PATH + "/" + boardId + "/members")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(inviteBody("someone@pivot.invalid", null)))
                .andExpect(status().isUnauthorized());
    }

    /**
     * Builds a fresh board owned by the given caller and returns its id.
     */
    private UUID createBoard(final String ownerBearerToken, final String title) throws Exception {
        String response = mockMvc.perform(post(BOARDS_PATH)
                        .header("Authorization", "Bearer " + ownerBearerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\": \"" + title + "\"}"))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse().getContentAsString();
        return UUID.fromString(mapper.readTree(response).get("id").asText());
    }

    private static String inviteBody(final String email, final String role) {
        return role == null
                ? "{\"email\": \"" + email + "\"}"
                : "{\"email\": \"" + email + "\", \"role\": \"" + role + "\"}";
    }

    private List<BoardMembershipNotificationRequestedEvent> notificationsFor(
            final long userId, final Kind kind) {
        return events.stream(BoardMembershipNotificationRequestedEvent.class)
                .filter(e -> e.recipientUserId() == userId && e.kind() == kind)
                .toList();
    }
}

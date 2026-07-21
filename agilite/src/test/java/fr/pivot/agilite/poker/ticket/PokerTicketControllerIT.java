package fr.pivot.agilite.poker.ticket;

import fr.pivot.agilite.AbstractAgiliteIntegrationTest;
import fr.pivot.agilite.poker.vote.PokerVote;
import fr.pivot.agilite.poker.vote.PokerVoteRepository;
import fr.pivot.agilite.testsupport.PlatformAuthTestSupport;
import fr.pivot.agilite.testsupport.PlatformAuthTestSupport.AuthFixture;
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

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration tests for {@link PokerTicketController} exercising the full Spring context against
 * a real PostgreSQL database (Testcontainers) — covers US09.2.1 ticket creation/lookup, US09.2.2
 * revelation, and US09.2.3 reset/finalization acceptance criteria.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class PokerTicketControllerIT extends AbstractAgiliteIntegrationTest {

    private static final String ROOMS_PATH = "/agilite/poker/rooms";

    @Autowired
    private WebApplicationContext wac;

    @Autowired
    private PokerVoteRepository voteRepository;

    @Autowired
    private PokerTicketRepository ticketRepository;

    private MockMvc mockMvc;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private String facilitatorToken;
    private String otherUserToken;
    private String otherTenantToken;

    @BeforeEach
    void setUp() throws Exception {
        mockMvc = MockMvcBuilders.webAppContextSetup(wac).build();

        AuthFixture facilitator = PlatformAuthTestSupport.seedActiveUserWithToken(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        facilitatorToken = facilitator.rawToken();

        // Another user in the SAME tenant as the facilitator — used for the 403
        // facilitator-only AC. seedActiveUserWithToken() always mints a brand new tenant, so
        // the same-tenant second user is composed from the lower-level seedUser()/issueToken()
        // primitives against the facilitator's own tenantId instead.
        long sameTenantUserId = PlatformAuthTestSupport.seedUser(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword(),
                facilitator.tenantId(), true);
        otherUserToken = PlatformAuthTestSupport.issueToken(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword(),
                sameTenantUserId, "active", java.time.Instant.now().plusSeconds(3600));

        // A user in a completely different tenant — used for the 404 cross-tenant AC.
        AuthFixture otherTenant = PlatformAuthTestSupport.seedActiveUserWithToken(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        otherTenantToken = otherTenant.rawToken();
    }

    /**
     * Given the room's facilitator, when {@code POST .../tickets} is called with a valid title,
     * then it returns HTTP 201 with {@code id}, {@code roomId}, {@code title}, {@code status ==
     * "VOTING"}, {@code createdAt}.
     */
    @Test
    void createTicket_asFacilitator_returnsCreatedTicket() throws Exception {
        String roomId = createRoom(facilitatorToken);

        mockMvc.perform(post(ROOMS_PATH + "/" + roomId + "/tickets")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer " + facilitatorToken)
                        .content("{\"title\": \"Estimate JIRA-123\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.roomId").value(roomId))
                .andExpect(jsonPath("$.title").value("Estimate JIRA-123"))
                .andExpect(jsonPath("$.status").value("VOTING"))
                .andExpect(jsonPath("$.createdAt").isNotEmpty());
    }

    /**
     * Given no ticket created yet, when {@code GET .../tickets/current} is called, then it
     * returns HTTP 200 with a {@code null} body — a legitimate state, not an error.
     */
    @Test
    void currentTicket_noneCreatedYet_returnsNullBody() throws Exception {
        String roomId = createRoom(facilitatorToken);

        MvcResult result = mockMvc.perform(get(ROOMS_PATH + "/" + roomId + "/tickets/current")
                        .header("Authorization", "Bearer " + facilitatorToken))
                .andExpect(status().isOk())
                .andReturn();

        String body = result.getResponse().getContentAsString();
        assertThat(body.isBlank() || "null".equals(body.trim())).isTrue();
    }

    /**
     * Given a ticket already created, when {@code GET .../tickets/current} is called by a
     * participant (not necessarily the facilitator), then it returns that same ticket.
     */
    @Test
    void currentTicket_activeTicketExists_returnsIt() throws Exception {
        String roomId = createRoom(facilitatorToken);
        String ticketId = createTicket(roomId, facilitatorToken, "Estimate JIRA-123");

        mockMvc.perform(get(ROOMS_PATH + "/" + roomId + "/tickets/current")
                        .header("Authorization", "Bearer " + otherUserToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(ticketId))
                .andExpect(jsonPath("$.title").value("Estimate JIRA-123"));
    }

    /**
     * Security AC: given an authenticated, same-tenant caller who is not the room's facilitator,
     * when {@code POST .../tickets} is called, then it returns HTTP 403 with code
     * {@code FACILITATOR_ONLY}.
     */
    @Test
    void createTicket_notFacilitator_returns403() throws Exception {
        String roomId = createRoom(facilitatorToken);

        mockMvc.perform(post(ROOMS_PATH + "/" + roomId + "/tickets")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer " + otherUserToken)
                        .content("{\"title\": \"Estimate JIRA-123\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FACILITATOR_ONLY"));
    }

    /**
     * Error case: given a room that already has a {@code VOTING} ticket, when the facilitator
     * attempts to create another one, then it returns HTTP 409 with code
     * {@code ACTIVE_TICKET_EXISTS}.
     */
    @Test
    void createTicket_activeTicketAlreadyExists_returns409() throws Exception {
        String roomId = createRoom(facilitatorToken);
        createTicket(roomId, facilitatorToken, "First ticket");

        mockMvc.perform(post(ROOMS_PATH + "/" + roomId + "/tickets")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer " + facilitatorToken)
                        .content("{\"title\": \"Second ticket\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ACTIVE_TICKET_EXISTS"));
    }

    /**
     * Error case: given a blank title, when {@code POST .../tickets} is called, then it returns
     * HTTP 400 with code {@code INVALID_TITLE}.
     */
    @Test
    void createTicket_blankTitle_returns400() throws Exception {
        String roomId = createRoom(facilitatorToken);

        mockMvc.perform(post(ROOMS_PATH + "/" + roomId + "/tickets")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer " + facilitatorToken)
                        .content("{\"title\": \"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_TITLE"));
    }

    /**
     * Error case: given a title longer than 200 characters, when {@code POST .../tickets} is
     * called, then it returns HTTP 400 with code {@code INVALID_TITLE}.
     */
    @Test
    void createTicket_tooLongTitle_returns400() throws Exception {
        String roomId = createRoom(facilitatorToken);
        String longTitle = "a".repeat(201);

        mockMvc.perform(post(ROOMS_PATH + "/" + roomId + "/tickets")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer " + facilitatorToken)
                        .content("{\"title\": \"" + longTitle + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_TITLE"));
    }

    /**
     * Security: given a room belonging to another tenant, when {@code POST .../tickets} is
     * called, then it returns HTTP 404 — never confirms cross-tenant existence, never 403.
     */
    @Test
    void createTicket_crossTenantRoom_returns404() throws Exception {
        String roomId = createRoom(facilitatorToken);

        mockMvc.perform(post(ROOMS_PATH + "/" + roomId + "/tickets")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer " + otherTenantToken)
                        .content("{\"title\": \"Title\"}"))
                .andExpect(status().isNotFound());
    }

    /**
     * Error case: given the Authorization bearer header is absent, when {@code POST
     * .../tickets} is called, then it returns HTTP 401.
     */
    @Test
    void createTicket_missingAuthorization_returns401() throws Exception {
        String roomId = createRoom(facilitatorToken);

        mockMvc.perform(post(ROOMS_PATH + "/" + roomId + "/tickets")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\": \"Title\"}"))
                .andExpect(status().isUnauthorized());
    }

    /**
     * Security-critical AC: given a {@code VOTING} ticket with cast votes, when the facilitator
     * reveals it, then the ticket transitions in the database from {@code VOTING}/{@code
     * revealedAt == null} to {@code REVEALED}/{@code revealedAt} non-null, the response carries
     * every vote attributed to a name and the computed consensus, and the raw JSON never carries
     * a {@code participantKey} or any other identity field (E09 — attributed reveal replaces the
     * pre-E09 anonymous {@code values} shape with {@code attributedVotes}; {@code seedVote} here
     * writes a raw {@code PokerVote} row with no matching roster entry, so every attributed vote
     * falls back to the generic placeholder name — proving that fallback end-to-end — while the
     * unit tests, mocking the roster, prove the real-name attribution path).
     */
    @Test
    void revealTicket_asFacilitatorWithVotes_transitionsStatusAndReturnsConsensus() throws Exception {
        String roomId = createRoom(facilitatorToken);
        String ticketId = createTicket(roomId, facilitatorToken, "Estimate JIRA-123");

        assertThat(ticketRepository.findById(UUID.fromString(ticketId)).orElseThrow().getStatus())
                .isEqualTo(PokerTicketStatus.VOTING);
        assertThat(ticketRepository.findById(UUID.fromString(ticketId)).orElseThrow().getRevealedAt())
                .isNull();

        seedVote(ticketId, "3");
        seedVote(ticketId, "5");
        seedVote(ticketId, "5");

        MvcResult result = mockMvc.perform(post(ROOMS_PATH + "/" + roomId + "/tickets/" + ticketId + "/reveal")
                        .header("Authorization", "Bearer " + facilitatorToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(ticketId))
                .andExpect(jsonPath("$.status").value("REVEALED"))
                .andExpect(jsonPath("$.revealedAt").isNotEmpty())
                .andExpect(jsonPath(
                        "$.attributedVotes[*].value", org.hamcrest.Matchers.containsInAnyOrder("3", "5", "5")))
                .andExpect(jsonPath(
                        "$.attributedVotes[*].name",
                        org.hamcrest.Matchers.everyItem(org.hamcrest.Matchers.equalTo("Participant"))))
                .andExpect(jsonPath("$.consensus.mean").value(4.3))
                .andExpect(jsonPath("$.consensus.median").value(5.0))
                .andExpect(jsonPath("$.consensus.majority").value("5"))
                .andReturn();

        String body = result.getResponse().getContentAsString();
        assertThat(body).doesNotContain("participantKey").doesNotContain("userId");

        PokerTicket persisted = ticketRepository.findById(UUID.fromString(ticketId)).orElseThrow();
        assertThat(persisted.getStatus()).isEqualTo(PokerTicketStatus.REVEALED);
        assertThat(persisted.getRevealedAt()).isNotNull();
    }

    /**
     * Given a ticket with zero cast votes, when it is revealed, then it still succeeds with an
     * empty {@code attributedVotes} array and an all-{@code null} consensus (no completeness
     * gate).
     */
    @Test
    void revealTicket_noVotesCast_succeedsWithNullConsensus() throws Exception {
        String roomId = createRoom(facilitatorToken);
        String ticketId = createTicket(roomId, facilitatorToken, "Estimate JIRA-123");

        mockMvc.perform(post(ROOMS_PATH + "/" + roomId + "/tickets/" + ticketId + "/reveal")
                        .header("Authorization", "Bearer " + facilitatorToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.attributedVotes", org.hamcrest.Matchers.empty()))
                .andExpect(jsonPath("$.consensus.mean").doesNotExist())
                .andExpect(jsonPath("$.consensus.median").doesNotExist())
                .andExpect(jsonPath("$.consensus.majority").doesNotExist());
    }

    /**
     * Security AC: given an authenticated, same-tenant caller who is not the room's facilitator,
     * when {@code POST .../reveal} is called, then it returns HTTP 403 with code {@code
     * FACILITATOR_ONLY} (the same mechanism as ticket creation, US09.2.1) and the ticket is left
     * unchanged ({@code VOTING}, {@code revealedAt == null}).
     */
    @Test
    void revealTicket_notFacilitator_returns403AndLeavesTicketUnchanged() throws Exception {
        String roomId = createRoom(facilitatorToken);
        String ticketId = createTicket(roomId, facilitatorToken, "Estimate JIRA-123");

        mockMvc.perform(post(ROOMS_PATH + "/" + roomId + "/tickets/" + ticketId + "/reveal")
                        .header("Authorization", "Bearer " + otherUserToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FACILITATOR_ONLY"));

        PokerTicket persisted = ticketRepository.findById(UUID.fromString(ticketId)).orElseThrow();
        assertThat(persisted.getStatus()).isEqualTo(PokerTicketStatus.VOTING);
        assertThat(persisted.getRevealedAt()).isNull();
    }

    /**
     * Security: given a room belonging to another tenant, when {@code POST .../reveal} is
     * called, then it returns HTTP 404 — never confirms cross-tenant existence, never 403.
     */
    @Test
    void revealTicket_crossTenantRoom_returns404() throws Exception {
        String roomId = createRoom(facilitatorToken);
        String ticketId = createTicket(roomId, facilitatorToken, "Estimate JIRA-123");

        mockMvc.perform(post(ROOMS_PATH + "/" + roomId + "/tickets/" + ticketId + "/reveal")
                        .header("Authorization", "Bearer " + otherTenantToken))
                .andExpect(status().isNotFound());
    }

    /**
     * Error case: given a {@code ticketId} that does not exist, when {@code POST .../reveal} is
     * called, then it returns HTTP 404.
     */
    @Test
    void revealTicket_ticketDoesNotExist_returns404() throws Exception {
        String roomId = createRoom(facilitatorToken);
        String unknownTicketId = UUID.randomUUID().toString();

        mockMvc.perform(post(ROOMS_PATH + "/" + roomId + "/tickets/" + unknownTicketId + "/reveal")
                        .header("Authorization", "Bearer " + facilitatorToken))
                .andExpect(status().isNotFound());
    }

    /**
     * Error case: given a ticket that exists but belongs to a different room than the one in the
     * request path, when {@code POST .../reveal} is called, then it returns HTTP 404 — never
     * confirms cross-room existence.
     */
    @Test
    void revealTicket_crossRoomTicket_returns404() throws Exception {
        String roomId = createRoom(facilitatorToken);
        String ticketId = createTicket(roomId, facilitatorToken, "Estimate JIRA-123");
        String otherRoomId = createRoom(facilitatorToken);

        mockMvc.perform(post(ROOMS_PATH + "/" + otherRoomId + "/tickets/" + ticketId + "/reveal")
                        .header("Authorization", "Bearer " + facilitatorToken))
                .andExpect(status().isNotFound());
    }

    /**
     * Error case: given a ticket already {@code REVEALED}, when {@code POST .../reveal} is
     * called a second time, then it returns HTTP 409 with code {@code TICKET_ALREADY_REVEALED}.
     */
    @Test
    void revealTicket_alreadyRevealed_returns409() throws Exception {
        String roomId = createRoom(facilitatorToken);
        String ticketId = createTicket(roomId, facilitatorToken, "Estimate JIRA-123");

        mockMvc.perform(post(ROOMS_PATH + "/" + roomId + "/tickets/" + ticketId + "/reveal")
                        .header("Authorization", "Bearer " + facilitatorToken))
                .andExpect(status().isOk());

        mockMvc.perform(post(ROOMS_PATH + "/" + roomId + "/tickets/" + ticketId + "/reveal")
                        .header("Authorization", "Bearer " + facilitatorToken))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("TICKET_ALREADY_REVEALED"));
    }

    /**
     * Error case: given the Authorization bearer header is absent, when {@code POST
     * .../reveal} is called, then it returns HTTP 401.
     */
    @Test
    void revealTicket_missingAuthorization_returns401() throws Exception {
        String roomId = createRoom(facilitatorToken);
        String ticketId = createTicket(roomId, facilitatorToken, "Estimate JIRA-123");

        mockMvc.perform(post(ROOMS_PATH + "/" + roomId + "/tickets/" + ticketId + "/reveal"))
                .andExpect(status().isUnauthorized());
    }

    // -------------------------------------------------------------------------
    // POST /poker/rooms/{roomId}/tickets/{ticketId}/reset (US09.2.3)
    // -------------------------------------------------------------------------

    /**
     * Security: given a revealed ticket with cast votes, when {@code POST .../reset} is called
     * by the facilitator, then it returns HTTP 200 with {@code status == "VOTING"} and {@code
     * revealedAt == null}, and every {@link PokerVote} row for that ticket is actually deleted
     * from the database (test TI obligatoire, AC Sécurité).
     */
    @Test
    void resetTicket_asFacilitatorOnRevealedTicket_deletesVotesAndReturnsVoting() throws Exception {
        String roomId = createRoom(facilitatorToken);
        String ticketId = createTicket(roomId, facilitatorToken, "Estimate JIRA-123");
        seedVote(ticketId, "3");
        seedVote(ticketId, "5");
        mockMvc.perform(post(ROOMS_PATH + "/" + roomId + "/tickets/" + ticketId + "/reveal")
                        .header("Authorization", "Bearer " + facilitatorToken))
                .andExpect(status().isOk());
        assertThat(voteRepository.findByTicketId(UUID.fromString(ticketId))).hasSize(2);

        mockMvc.perform(post(ROOMS_PATH + "/" + roomId + "/tickets/" + ticketId + "/reset")
                        .header("Authorization", "Bearer " + facilitatorToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(ticketId))
                .andExpect(jsonPath("$.status").value("VOTING"))
                .andExpect(jsonPath("$.revealedAt").doesNotExist());

        assertThat(voteRepository.findByTicketId(UUID.fromString(ticketId))).isEmpty();
        PokerTicket persisted = ticketRepository.findById(UUID.fromString(ticketId)).orElseThrow();
        assertThat(persisted.getStatus()).isEqualTo(PokerTicketStatus.VOTING);
        assertThat(persisted.getRevealedAt()).isNull();
    }

    /**
     * Given a ticket reset then revoted, when it is revealed again, then the consensus reflects
     * only the current round's votes — no trace of the votes erased by the reset.
     */
    @Test
    void resetTicket_thenRevoteAndReveal_consensusOnlyReflectsPostResetVotes() throws Exception {
        String roomId = createRoom(facilitatorToken);
        String ticketId = createTicket(roomId, facilitatorToken, "Estimate JIRA-123");
        seedVote(ticketId, "1");
        mockMvc.perform(post(ROOMS_PATH + "/" + roomId + "/tickets/" + ticketId + "/reveal")
                        .header("Authorization", "Bearer " + facilitatorToken))
                .andExpect(status().isOk());
        mockMvc.perform(post(ROOMS_PATH + "/" + roomId + "/tickets/" + ticketId + "/reset")
                        .header("Authorization", "Bearer " + facilitatorToken))
                .andExpect(status().isOk());
        seedVote(ticketId, "8");
        seedVote(ticketId, "8");

        mockMvc.perform(post(ROOMS_PATH + "/" + roomId + "/tickets/" + ticketId + "/reveal")
                        .header("Authorization", "Bearer " + facilitatorToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath(
                        "$.attributedVotes[*].value", org.hamcrest.Matchers.containsInAnyOrder("8", "8")))
                .andExpect(jsonPath("$.consensus.mean").value(8.0));
    }

    /**
     * Error case: given a ticket still {@code VOTING} (never revealed), when {@code
     * POST .../reset} is called, then it returns HTTP 409 with code {@code TICKET_NOT_REVEALED}.
     */
    @Test
    void resetTicket_ticketStillVoting_returns409() throws Exception {
        String roomId = createRoom(facilitatorToken);
        String ticketId = createTicket(roomId, facilitatorToken, "Estimate JIRA-123");

        mockMvc.perform(post(ROOMS_PATH + "/" + roomId + "/tickets/" + ticketId + "/reset")
                        .header("Authorization", "Bearer " + facilitatorToken))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("TICKET_NOT_REVEALED"));
    }

    /**
     * Error case: given a ticket already finalized, when {@code POST .../reset} is called, then
     * it returns HTTP 409 with code {@code TICKET_ALREADY_FINALIZED} and the persisted final
     * estimate is left unchanged (test TI obligatoire, AC Sécurité).
     */
    @Test
    void resetTicket_ticketAlreadyFinalized_returns409AndLeavesFinalEstimateUnchanged() throws Exception {
        String roomId = createRoom(facilitatorToken);
        String ticketId = createTicket(roomId, facilitatorToken, "Estimate JIRA-123");
        mockMvc.perform(post(ROOMS_PATH + "/" + roomId + "/tickets/" + ticketId + "/reveal")
                        .header("Authorization", "Bearer " + facilitatorToken))
                .andExpect(status().isOk());
        mockMvc.perform(post(ROOMS_PATH + "/" + roomId + "/tickets/" + ticketId + "/finalize")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer " + facilitatorToken)
                        .content("{\"finalEstimate\": \"5\"}"))
                .andExpect(status().isOk());

        mockMvc.perform(post(ROOMS_PATH + "/" + roomId + "/tickets/" + ticketId + "/reset")
                        .header("Authorization", "Bearer " + facilitatorToken))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("TICKET_ALREADY_FINALIZED"));

        assertThat(ticketRepository.findById(UUID.fromString(ticketId)).orElseThrow().getFinalEstimate())
                .isEqualTo("5");
    }

    /**
     * Security AC: given an authenticated, same-tenant caller who is not the room's facilitator,
     * when {@code POST .../reset} is called, then it returns HTTP 403 with code {@code
     * FACILITATOR_ONLY} and the ticket is left unchanged (test TI obligatoire, AC Sécurité).
     */
    @Test
    void resetTicket_notFacilitator_returns403AndLeavesTicketUnchanged() throws Exception {
        String roomId = createRoom(facilitatorToken);
        String ticketId = createTicket(roomId, facilitatorToken, "Estimate JIRA-123");
        mockMvc.perform(post(ROOMS_PATH + "/" + roomId + "/tickets/" + ticketId + "/reveal")
                        .header("Authorization", "Bearer " + facilitatorToken))
                .andExpect(status().isOk());

        mockMvc.perform(post(ROOMS_PATH + "/" + roomId + "/tickets/" + ticketId + "/reset")
                        .header("Authorization", "Bearer " + otherUserToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FACILITATOR_ONLY"));

        PokerTicket persisted = ticketRepository.findById(UUID.fromString(ticketId)).orElseThrow();
        assertThat(persisted.getStatus()).isEqualTo(PokerTicketStatus.REVEALED);
    }

    /**
     * Security: given a room belonging to another tenant, when {@code POST .../reset} is called,
     * then it returns HTTP 404 — never confirms cross-tenant existence.
     */
    @Test
    void resetTicket_crossTenantRoom_returns404() throws Exception {
        String roomId = createRoom(facilitatorToken);
        String ticketId = createTicket(roomId, facilitatorToken, "Estimate JIRA-123");

        mockMvc.perform(post(ROOMS_PATH + "/" + roomId + "/tickets/" + ticketId + "/reset")
                        .header("Authorization", "Bearer " + otherTenantToken))
                .andExpect(status().isNotFound());
    }

    /**
     * Error case: given a ticket that exists but belongs to a different room than the one in the
     * request path, when {@code POST .../reset} is called, then it returns HTTP 404 — never
     * confirms cross-room existence.
     */
    @Test
    void resetTicket_crossRoomTicket_returns404() throws Exception {
        String roomId = createRoom(facilitatorToken);
        String ticketId = createTicket(roomId, facilitatorToken, "Estimate JIRA-123");
        mockMvc.perform(post(ROOMS_PATH + "/" + roomId + "/tickets/" + ticketId + "/reveal")
                        .header("Authorization", "Bearer " + facilitatorToken))
                .andExpect(status().isOk());
        String otherRoomId = createRoom(facilitatorToken);

        mockMvc.perform(post(ROOMS_PATH + "/" + otherRoomId + "/tickets/" + ticketId + "/reset")
                        .header("Authorization", "Bearer " + facilitatorToken))
                .andExpect(status().isNotFound());
    }

    /**
     * Error case: given the Authorization bearer header is absent, when {@code
     * POST .../reset} is called, then it returns HTTP 401.
     */
    @Test
    void resetTicket_missingAuthorization_returns401() throws Exception {
        String roomId = createRoom(facilitatorToken);
        String ticketId = createTicket(roomId, facilitatorToken, "Estimate JIRA-123");

        mockMvc.perform(post(ROOMS_PATH + "/" + roomId + "/tickets/" + ticketId + "/reset"))
                .andExpect(status().isUnauthorized());
    }

    // -------------------------------------------------------------------------
    // POST /poker/rooms/{roomId}/tickets/{ticketId}/finalize (US09.2.3)
    // -------------------------------------------------------------------------

    /**
     * Given the room's facilitator and a revealed ticket, when {@code POST .../finalize} is
     * called with a value from the room's deck, then it returns HTTP 200 with {@code status ==
     * "REVEALED"} and the persisted {@code finalEstimate}.
     */
    @Test
    void finalizeTicket_asFacilitatorWithValidValue_persistsAndReturnsIt() throws Exception {
        String roomId = createRoom(facilitatorToken);
        String ticketId = createTicket(roomId, facilitatorToken, "Estimate JIRA-123");
        mockMvc.perform(post(ROOMS_PATH + "/" + roomId + "/tickets/" + ticketId + "/reveal")
                        .header("Authorization", "Bearer " + facilitatorToken))
                .andExpect(status().isOk());

        mockMvc.perform(post(ROOMS_PATH + "/" + roomId + "/tickets/" + ticketId + "/finalize")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer " + facilitatorToken)
                        .content("{\"finalEstimate\": \"5\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(ticketId))
                .andExpect(jsonPath("$.status").value("REVEALED"))
                .andExpect(jsonPath("$.finalEstimate").value("5"));

        PokerTicket persisted = ticketRepository.findById(UUID.fromString(ticketId)).orElseThrow();
        assertThat(persisted.getFinalEstimate()).isEqualTo("5");
    }

    /**
     * Error case: given a {@code finalEstimate} absent from the room's own deck, when {@code
     * POST .../finalize} is called, then it returns HTTP 400 with a message listing the accepted
     * values.
     */
    @Test
    void finalizeTicket_valueNotInDeck_returns400WithAcceptedValues() throws Exception {
        String roomId = createRoom(facilitatorToken);
        String ticketId = createTicket(roomId, facilitatorToken, "Estimate JIRA-123");
        mockMvc.perform(post(ROOMS_PATH + "/" + roomId + "/tickets/" + ticketId + "/reveal")
                        .header("Authorization", "Bearer " + facilitatorToken))
                .andExpect(status().isOk());

        mockMvc.perform(post(ROOMS_PATH + "/" + roomId + "/tickets/" + ticketId + "/finalize")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer " + facilitatorToken)
                        .content("{\"finalEstimate\": \"999\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail", org.hamcrest.Matchers.containsString("3")));
    }

    /**
     * Error case: given an absent/blank {@code finalEstimate}, when {@code POST .../finalize} is
     * called, then it returns HTTP 400 with code {@code INVALID_FINAL_ESTIMATE}.
     */
    @Test
    void finalizeTicket_blankValue_returns400() throws Exception {
        String roomId = createRoom(facilitatorToken);
        String ticketId = createTicket(roomId, facilitatorToken, "Estimate JIRA-123");
        mockMvc.perform(post(ROOMS_PATH + "/" + roomId + "/tickets/" + ticketId + "/reveal")
                        .header("Authorization", "Bearer " + facilitatorToken))
                .andExpect(status().isOk());

        mockMvc.perform(post(ROOMS_PATH + "/" + roomId + "/tickets/" + ticketId + "/finalize")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer " + facilitatorToken)
                        .content("{\"finalEstimate\": \"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_FINAL_ESTIMATE"));
    }

    /**
     * Error case: given a ticket still {@code VOTING}, when {@code POST .../finalize} is called,
     * then it returns HTTP 409 with code {@code TICKET_NOT_REVEALED}.
     */
    @Test
    void finalizeTicket_ticketStillVoting_returns409() throws Exception {
        String roomId = createRoom(facilitatorToken);
        String ticketId = createTicket(roomId, facilitatorToken, "Estimate JIRA-123");

        mockMvc.perform(post(ROOMS_PATH + "/" + roomId + "/tickets/" + ticketId + "/finalize")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer " + facilitatorToken)
                        .content("{\"finalEstimate\": \"5\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("TICKET_NOT_REVEALED"));
    }

    /**
     * Error case: given a ticket already finalized, when a second {@code POST .../finalize} is
     * called, then it returns HTTP 409 with code {@code TICKET_ALREADY_FINALIZED} and the
     * originally persisted value is left unchanged (test TI obligatoire, AC Sécurité).
     */
    @Test
    void finalizeTicket_alreadyFinalized_returns409AndLeavesValueUnchanged() throws Exception {
        String roomId = createRoom(facilitatorToken);
        String ticketId = createTicket(roomId, facilitatorToken, "Estimate JIRA-123");
        mockMvc.perform(post(ROOMS_PATH + "/" + roomId + "/tickets/" + ticketId + "/reveal")
                        .header("Authorization", "Bearer " + facilitatorToken))
                .andExpect(status().isOk());
        mockMvc.perform(post(ROOMS_PATH + "/" + roomId + "/tickets/" + ticketId + "/finalize")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer " + facilitatorToken)
                        .content("{\"finalEstimate\": \"5\"}"))
                .andExpect(status().isOk());

        mockMvc.perform(post(ROOMS_PATH + "/" + roomId + "/tickets/" + ticketId + "/finalize")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer " + facilitatorToken)
                        .content("{\"finalEstimate\": \"8\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("TICKET_ALREADY_FINALIZED"));

        assertThat(ticketRepository.findById(UUID.fromString(ticketId)).orElseThrow().getFinalEstimate())
                .isEqualTo("5");
    }

    /**
     * Security AC: given an authenticated, same-tenant caller who is not the room's facilitator,
     * when {@code POST .../finalize} is called, then it returns HTTP 403 with code {@code
     * FACILITATOR_ONLY} and nothing is persisted (test TI obligatoire, AC Sécurité).
     */
    @Test
    void finalizeTicket_notFacilitator_returns403() throws Exception {
        String roomId = createRoom(facilitatorToken);
        String ticketId = createTicket(roomId, facilitatorToken, "Estimate JIRA-123");
        mockMvc.perform(post(ROOMS_PATH + "/" + roomId + "/tickets/" + ticketId + "/reveal")
                        .header("Authorization", "Bearer " + facilitatorToken))
                .andExpect(status().isOk());

        mockMvc.perform(post(ROOMS_PATH + "/" + roomId + "/tickets/" + ticketId + "/finalize")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer " + otherUserToken)
                        .content("{\"finalEstimate\": \"5\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FACILITATOR_ONLY"));

        assertThat(ticketRepository.findById(UUID.fromString(ticketId)).orElseThrow().getFinalEstimate())
                .isNull();
    }

    /**
     * Security: given a room belonging to another tenant, when {@code POST .../finalize} is
     * called, then it returns HTTP 404 — never confirms cross-tenant existence.
     */
    @Test
    void finalizeTicket_crossTenantRoom_returns404() throws Exception {
        String roomId = createRoom(facilitatorToken);
        String ticketId = createTicket(roomId, facilitatorToken, "Estimate JIRA-123");

        mockMvc.perform(post(ROOMS_PATH + "/" + roomId + "/tickets/" + ticketId + "/finalize")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer " + otherTenantToken)
                        .content("{\"finalEstimate\": \"5\"}"))
                .andExpect(status().isNotFound());
    }

    /**
     * Error case: given the Authorization bearer header is absent, when {@code
     * POST .../finalize} is called, then it returns HTTP 401.
     */
    @Test
    void finalizeTicket_missingAuthorization_returns401() throws Exception {
        String roomId = createRoom(facilitatorToken);
        String ticketId = createTicket(roomId, facilitatorToken, "Estimate JIRA-123");

        mockMvc.perform(post(ROOMS_PATH + "/" + roomId + "/tickets/" + ticketId + "/finalize")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"finalEstimate\": \"5\"}"))
                .andExpect(status().isUnauthorized());
    }

    // -------------------------------------------------------------------------
    // GET /poker/rooms/{roomId}/tickets/recap (E09 — end-of-session recap)
    // -------------------------------------------------------------------------

    /**
     * Given a room with one revealed ticket, when the recap is fetched, then it lists that
     * ticket with its attributed votes and consensus — not facilitator-restricted, any
     * authenticated same-tenant caller can read it (every ticket listed was already broadcast to
     * every participant at its own reveal time).
     */
    @Test
    void recap_roomWithOneRevealedTicket_listsItWithAttributedVotesAndConsensus() throws Exception {
        String roomId = createRoom(facilitatorToken);
        String ticketId = createTicket(roomId, facilitatorToken, "Estimate JIRA-123");
        seedVote(ticketId, "3");
        seedVote(ticketId, "5");
        mockMvc.perform(post(ROOMS_PATH + "/" + roomId + "/tickets/" + ticketId + "/reveal")
                        .header("Authorization", "Bearer " + facilitatorToken))
                .andExpect(status().isOk());

        mockMvc.perform(get(ROOMS_PATH + "/" + roomId + "/tickets/recap")
                        .header("Authorization", "Bearer " + facilitatorToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.roomId").value(roomId))
                .andExpect(jsonPath("$.tickets", org.hamcrest.Matchers.hasSize(1)))
                .andExpect(jsonPath("$.tickets[0].id").value(ticketId))
                .andExpect(jsonPath("$.tickets[0].title").value("Estimate JIRA-123"))
                .andExpect(jsonPath("$.tickets[0].revealedAt").isNotEmpty())
                .andExpect(jsonPath(
                        "$.tickets[0].attributedVotes[*].value",
                        org.hamcrest.Matchers.containsInAnyOrder("3", "5")))
                .andExpect(jsonPath("$.tickets[0].consensus.mean").value(4.0));
    }

    /**
     * Given a room with one finalized ticket, when the recap is fetched, then its entry carries
     * the persisted {@code finalEstimate} (US09.2.3) — a finalized ticket remains consultable
     * indefinitely with its retained value via this same read endpoint.
     */
    @Test
    void recap_ticketFinalized_entryCarriesFinalEstimate() throws Exception {
        String roomId = createRoom(facilitatorToken);
        String ticketId = createTicket(roomId, facilitatorToken, "Estimate JIRA-123");
        mockMvc.perform(post(ROOMS_PATH + "/" + roomId + "/tickets/" + ticketId + "/reveal")
                        .header("Authorization", "Bearer " + facilitatorToken))
                .andExpect(status().isOk());
        mockMvc.perform(post(ROOMS_PATH + "/" + roomId + "/tickets/" + ticketId + "/finalize")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer " + facilitatorToken)
                        .content("{\"finalEstimate\": \"5\"}"))
                .andExpect(status().isOk());

        mockMvc.perform(get(ROOMS_PATH + "/" + roomId + "/tickets/recap")
                        .header("Authorization", "Bearer " + facilitatorToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tickets[0].finalEstimate").value("5"));
    }

    /**
     * Given a room with no revealed ticket yet, when the recap is fetched, then it returns an
     * empty ticket list rather than an error.
     */
    @Test
    void recap_noRevealedTicketsYet_returnsEmptyList() throws Exception {
        String roomId = createRoom(facilitatorToken);
        createTicket(roomId, facilitatorToken, "Estimate JIRA-123");

        mockMvc.perform(get(ROOMS_PATH + "/" + roomId + "/tickets/recap")
                        .header("Authorization", "Bearer " + facilitatorToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tickets", org.hamcrest.Matchers.empty()));
    }

    /**
     * Security AC: a non-facilitator, same-tenant caller can still read the recap — this endpoint
     * is deliberately not facilitator-restricted (unlike create/reveal).
     */
    @Test
    void recap_asNonFacilitatorSameTenant_returns200() throws Exception {
        String roomId = createRoom(facilitatorToken);
        String ticketId = createTicket(roomId, facilitatorToken, "Estimate JIRA-123");
        mockMvc.perform(post(ROOMS_PATH + "/" + roomId + "/tickets/" + ticketId + "/reveal")
                        .header("Authorization", "Bearer " + facilitatorToken))
                .andExpect(status().isOk());

        mockMvc.perform(get(ROOMS_PATH + "/" + roomId + "/tickets/recap")
                        .header("Authorization", "Bearer " + otherUserToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tickets", org.hamcrest.Matchers.hasSize(1)));
    }

    /**
     * Security AC: given a room belonging to another tenant, when the recap is fetched, then it
     * returns 404 — never confirms cross-tenant existence.
     */
    @Test
    void recap_crossTenantRoom_returns404() throws Exception {
        String roomId = createRoom(facilitatorToken);

        mockMvc.perform(get(ROOMS_PATH + "/" + roomId + "/tickets/recap")
                        .header("Authorization", "Bearer " + otherTenantToken))
                .andExpect(status().isNotFound());
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private String createRoom(final String token) throws Exception {
        MvcResult result = mockMvc.perform(post(ROOMS_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer " + token)
                        .content("{\"name\": \"Room\"}"))
                .andExpect(status().isCreated())
                .andReturn();
        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        return body.get("id").asText();
    }

    private String createTicket(final String roomId, final String token, final String title) throws Exception {
        MvcResult result = mockMvc.perform(post(ROOMS_PATH + "/" + roomId + "/tickets")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer " + token)
                        .content("{\"title\": \"" + title + "\"}"))
                .andExpect(status().isCreated())
                .andReturn();
        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        return body.get("id").asText();
    }

    /**
     * Seeds a vote directly via {@link PokerVoteRepository} — votes normally arrive over STOMP
     * (US09.2.1), but this IT only needs them to already exist in the database ahead of a reveal.
     *
     * @param ticketId the ticket to vote on
     * @param value    the raw card value
     */
    private void seedVote(final String ticketId, final String value) {
        voteRepository.save(new PokerVote(
                UUID.fromString(ticketId), "participant-key-" + UUID.randomUUID(), value, Instant.now()));
    }
}

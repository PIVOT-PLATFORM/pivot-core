package fr.pivot.collaboratif.meetops.booking;

import com.jayway.jsonpath.JsonPath;
import fr.pivot.collaboratif.AbstractCollaboratifIntegrationTest;
import fr.pivot.collaboratif.meeting.Meeting;
import fr.pivot.collaboratif.meeting.MeetingRepository;
import fr.pivot.collaboratif.meeting.MeetingStatus;
import fr.pivot.collaboratif.meetops.availability.InMemoryAvailabilityAdapter;
import fr.pivot.collaboratif.meetops.bus.BookingConfirmedEvent;
import fr.pivot.collaboratif.meetops.bus.RescheduleRequestedEvent;
import fr.pivot.collaboratif.meetops.bus.WindowCreatedEvent;
import fr.pivot.collaboratif.meetops.bus.WindowDeletedEvent;
import fr.pivot.collaboratif.meetops.bus.WindowEventListener;
import fr.pivot.collaboratif.meetops.bus.WindowUpdatedEvent;
import fr.pivot.collaboratif.testsupport.PlatformAuthTestSupport;
import fr.pivot.collaboratif.testsupport.PlatformAuthTestSupport.AuthFixture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.ApplicationListener;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration tests for the MeetOps booking flow (US12.4.1) against a real PostgreSQL database
 * (Testcontainers) — bus consumption ({@link WindowEventListener}, exercised directly via {@link
 * ApplicationEventPublisher#publishEvent}, "TI publiant directement" per the Gate 1 architecture
 * note) and the REST surface ({@link BookingController}, via MockMvc).
 *
 * <p>Mirrors {@code MeetingControllerIT}'s MockMvc-via-{@code webAppContextSetup} convention —
 * paths start with {@code /collaboratif/meetings}, not {@code /api/collaboratif/meetings}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(BookingControllerIT.EventRecordingConfig.class)
class BookingControllerIT extends AbstractCollaboratifIntegrationTest {

    private static final String BASE_PATH = "/collaboratif/meetings";

    @Autowired
    private WebApplicationContext wac;
    @Autowired
    private MeetingRepository meetingRepository;
    @Autowired
    private ProposedSlotRepository proposedSlotRepository;
    @Autowired
    private ApplicationEventPublisher eventPublisher;
    @Autowired
    private InMemoryAvailabilityAdapter availabilityAdapter;
    @Autowired
    private BookingConfirmedRecorder bookingConfirmedRecorder;
    @Autowired
    private InvitationRecorder invitationRecorder;
    @Autowired
    private RescheduleRecorder rescheduleRecorder;

    private MockMvc mockMvc;
    private AuthFixture organizer;
    private long tenantId;
    private String organizerEmail;

    @BeforeEach
    void setUp() throws Exception {
        mockMvc = MockMvcBuilders.webAppContextSetup(wac).build();
        availabilityAdapter.reset();
        bookingConfirmedRecorder.events.clear();
        invitationRecorder.events.clear();
        rescheduleRecorder.events.clear();

        tenantId = PlatformAuthTestSupport.seedTenant(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword(), null);
        organizerEmail = "organizer-" + UUID.randomUUID() + "@pivot.test";
        long organizerId = PlatformAuthTestSupport.seedUserWithEmail(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword(), tenantId, organizerEmail, true);
        String token = PlatformAuthTestSupport.issueToken(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword(), organizerId, "active",
                Instant.now().plusSeconds(3600));
        organizer = new AuthFixture(tenantId, organizerId, token);
    }

    // -------------------------------------------------------------------------
    // window.created -> PRE_RESERVED
    // -------------------------------------------------------------------------

    @Test
    void windowCreated_createsPreReservedMeeting_withRankedSlots_noInvitationSent() throws Exception {
        String eventRef = publishWindowCreated(List.of(organizerEmailOf()));

        Meeting meeting = meetingRepository.findByTenantIdAndEventRef(tenantId, eventRef).orElseThrow();
        assertThat(meeting.getStatus()).isEqualTo(MeetingStatus.PRE_RESERVED);
        assertThat(meeting.getEventRef()).isEqualTo(eventRef);
        assertThat(meeting.getCreatedBy()).isEqualTo(organizer.userId());

        List<ProposedSlot> slots = proposedSlotRepository.findByMeetingIdOrderByRank(meeting.getId());
        assertThat(slots).isNotEmpty();
        assertThat(slots.get(0).getRank()).isEqualTo(1);
        assertThat(slots.get(0).isHasConflict()).isFalse();

        assertThat(invitationRecorder.events).isEmpty();
    }

    @Test
    void windowCreated_duplicateEventRef_isIdempotent_noDuplicateMeeting() throws Exception {
        String eventRef = "evt-" + UUID.randomUUID();
        WindowCreatedEvent event = buildWindowCreatedEvent(eventRef, List.of(organizerEmailOf()));
        long countBefore = meetingRepository.count();

        eventPublisher.publishEvent(event);
        long countAfterFirst = meetingRepository.count();
        eventPublisher.publishEvent(event);
        long countAfterSecond = meetingRepository.count();

        assertThat(countAfterFirst).isEqualTo(countBefore + 1);
        assertThat(countAfterSecond).isEqualTo(countAfterFirst);
        assertThat(meetingRepository.findByTenantIdAndEventRef(tenantId, eventRef)).isPresent();
    }

    @Test
    void windowCreated_malformedEmptyParticipants_doesNotCreateMeeting_listenerDoesNotCrash() {
        String eventRef = "evt-" + UUID.randomUUID();
        WindowCreatedEvent malformed = new WindowCreatedEvent(
                tenantId, eventRef, "proj-1", "Sprint Review", List.of(),
                Instant.parse("2026-08-03T09:00:00Z"), Instant.parse("2026-08-03T11:00:00Z"), 30);

        eventPublisher.publishEvent(malformed);

        assertThat(meetingRepository.findByTenantIdAndEventRef(tenantId, eventRef)).isEmpty();
    }

    @Test
    void getById_organizer_returns200WithRankedSlots() throws Exception {
        String eventRef = publishWindowCreated(List.of(organizerEmailOf()));
        Meeting meeting = meetingRepository.findByTenantIdAndEventRef(tenantId, eventRef).orElseThrow();

        mockMvc.perform(get(BASE_PATH + "/" + meeting.getId())
                        .header("Authorization", organizer.authorizationHeader()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PRE_RESERVED"))
                .andExpect(jsonPath("$.eventRef").value(eventRef))
                .andExpect(jsonPath("$.proposedSlots[0].rank").value(1))
                .andExpect(jsonPath("$.proposedSlots[0].recommended").value(true));
    }

    // -------------------------------------------------------------------------
    // Sécurité — isolation tenant / autorisation
    // -------------------------------------------------------------------------

    @Test
    void getById_crossTenant_returns404() throws Exception {
        String eventRef = publishWindowCreated(List.of(organizerEmailOf()));
        Meeting meeting = meetingRepository.findByTenantIdAndEventRef(tenantId, eventRef).orElseThrow();

        AuthFixture otherTenantUser = PlatformAuthTestSupport.seedActiveUserWithToken(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());

        mockMvc.perform(get(BASE_PATH + "/" + meeting.getId())
                        .header("Authorization", otherTenantUser.authorizationHeader()))
                .andExpect(status().isNotFound());
    }

    @Test
    void confirm_byNonOrganizerSameTenant_returns403() throws Exception {
        String eventRef = publishWindowCreated(List.of(organizerEmailOf()));
        Meeting meeting = meetingRepository.findByTenantIdAndEventRef(tenantId, eventRef).orElseThrow();
        UUID slotId = proposedSlotRepository.findByMeetingIdOrderByRank(meeting.getId()).get(0).getId();

        String otherEmail = "colleague-" + UUID.randomUUID() + "@pivot.test";
        long otherUserId = PlatformAuthTestSupport.seedUserWithEmail(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword(), tenantId, otherEmail, true);
        String otherToken = PlatformAuthTestSupport.issueToken(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword(), otherUserId, "active",
                Instant.now().plusSeconds(3600));

        mockMvc.perform(post(BASE_PATH + "/" + meeting.getId() + "/confirm")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer " + otherToken)
                        .content("{\"slotId\":\"" + slotId + "\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void confirm_crossTenantMeetingId_returns404() throws Exception {
        String eventRef = publishWindowCreated(List.of(organizerEmailOf()));
        Meeting meeting = meetingRepository.findByTenantIdAndEventRef(tenantId, eventRef).orElseThrow();
        UUID slotId = proposedSlotRepository.findByMeetingIdOrderByRank(meeting.getId()).get(0).getId();

        AuthFixture otherTenantUser = PlatformAuthTestSupport.seedActiveUserWithToken(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());

        mockMvc.perform(post(BASE_PATH + "/" + meeting.getId() + "/confirm")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", otherTenantUser.authorizationHeader())
                        .content("{\"slotId\":\"" + slotId + "\"}"))
                .andExpect(status().isNotFound());
    }

    // -------------------------------------------------------------------------
    // Confirmation → CONFIRMED + bus
    // -------------------------------------------------------------------------

    @Test
    void confirm_byOrganizer_transitionsToConfirmed_publishesEvent_sendsInvitation() throws Exception {
        String eventRef = publishWindowCreated(List.of(organizerEmailOf()));
        Meeting meeting = meetingRepository.findByTenantIdAndEventRef(tenantId, eventRef).orElseThrow();
        UUID slotId = proposedSlotRepository.findByMeetingIdOrderByRank(meeting.getId()).get(0).getId();

        MvcResult result = mockMvc.perform(post(BASE_PATH + "/" + meeting.getId() + "/confirm")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", organizer.authorizationHeader())
                        .content("{\"slotId\":\"" + slotId + "\"}"))
                .andExpect(status().isOk())
                .andReturn();

        String status = JsonPath.read(result.getResponse().getContentAsString(), "$.status");
        assertThat(status).isEqualTo("CONFIRMED");

        Meeting reloaded = meetingRepository.findById(meeting.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(MeetingStatus.CONFIRMED);

        assertThat(bookingConfirmedRecorder.events).anyMatch(e -> e.meetingId().equals(meeting.getId()));
        assertThat(invitationRecorder.events).anyMatch(e -> e.meetingId().equals(meeting.getId()));
    }

    @Test
    void confirm_alreadyConfirmed_returns409_noDoublePublish() throws Exception {
        String eventRef = publishWindowCreated(List.of(organizerEmailOf()));
        Meeting meeting = meetingRepository.findByTenantIdAndEventRef(tenantId, eventRef).orElseThrow();
        UUID slotId = proposedSlotRepository.findByMeetingIdOrderByRank(meeting.getId()).get(0).getId();

        mockMvc.perform(post(BASE_PATH + "/" + meeting.getId() + "/confirm")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", organizer.authorizationHeader())
                        .content("{\"slotId\":\"" + slotId + "\"}"))
                .andExpect(status().isOk());
        int firstPublishCount = bookingConfirmedRecorder.events.size();

        mockMvc.perform(post(BASE_PATH + "/" + meeting.getId() + "/confirm")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", organizer.authorizationHeader())
                        .content("{\"slotId\":\"" + slotId + "\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ALREADY_CONFIRMED"));

        assertThat(bookingConfirmedRecorder.events).hasSize(firstPublishCount);
    }

    @Test
    void confirm_invalidSlot_returns422() throws Exception {
        String eventRef = publishWindowCreated(List.of(organizerEmailOf()));
        Meeting meeting = meetingRepository.findByTenantIdAndEventRef(tenantId, eventRef).orElseThrow();

        mockMvc.perform(post(BASE_PATH + "/" + meeting.getId() + "/confirm")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", organizer.authorizationHeader())
                        .content("{\"slotId\":\"" + UUID.randomUUID() + "\"}"))
                .andExpect(status().isUnprocessableEntity());
    }

    // -------------------------------------------------------------------------
    // Validation humaine — ajustement manuel
    // -------------------------------------------------------------------------

    @Test
    void adjustSlot_byOrganizer_updatesBoundaries_staysPreReserved() throws Exception {
        String eventRef = publishWindowCreated(List.of(organizerEmailOf()));
        Meeting meeting = meetingRepository.findByTenantIdAndEventRef(tenantId, eventRef).orElseThrow();
        UUID slotId = proposedSlotRepository.findByMeetingIdOrderByRank(meeting.getId()).get(0).getId();

        mockMvc.perform(patch(BASE_PATH + "/" + meeting.getId() + "/slot")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", organizer.authorizationHeader())
                        .content("""
                                {"slotId":"%s","start":"2026-08-03T14:00:00Z","end":"2026-08-03T14:30:00Z"}
                                """.formatted(slotId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PRE_RESERVED"));

        ProposedSlot reloaded = proposedSlotRepository.findById(slotId).orElseThrow();
        assertThat(reloaded.getSlotStart()).isEqualTo(Instant.parse("2026-08-03T14:00:00Z"));
    }

    // -------------------------------------------------------------------------
    // window.deleted / window.updated — cohérence
    // -------------------------------------------------------------------------

    @Test
    void windowDeleted_onPreReserved_cancelsMeeting() throws Exception {
        String eventRef = publishWindowCreated(List.of(organizerEmailOf()));
        Meeting meeting = meetingRepository.findByTenantIdAndEventRef(tenantId, eventRef).orElseThrow();

        eventPublisher.publishEvent(new WindowDeletedEvent(tenantId, eventRef));

        assertThat(meetingRepository.findById(meeting.getId())).isEmpty();
    }

    @Test
    void windowDeleted_onConfirmed_raisesRescheduleRequest_notCancelled() throws Exception {
        String eventRef = publishWindowCreated(List.of(organizerEmailOf()));
        Meeting meeting = meetingRepository.findByTenantIdAndEventRef(tenantId, eventRef).orElseThrow();
        UUID slotId = proposedSlotRepository.findByMeetingIdOrderByRank(meeting.getId()).get(0).getId();
        mockMvc.perform(post(BASE_PATH + "/" + meeting.getId() + "/confirm")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", organizer.authorizationHeader())
                        .content("{\"slotId\":\"" + slotId + "\"}"))
                .andExpect(status().isOk());

        eventPublisher.publishEvent(new WindowDeletedEvent(tenantId, eventRef));

        Meeting reloaded = meetingRepository.findById(meeting.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(MeetingStatus.CONFIRMED);
        assertThat(reloaded.isRescheduleRequested()).isTrue();
        assertThat(rescheduleRecorder.events).anyMatch(e -> e.meetingId().equals(meeting.getId()));
    }

    @Test
    void windowUpdated_onNonConfirmed_recomputesSlots() throws Exception {
        String eventRef = publishWindowCreated(List.of(organizerEmailOf()));
        Meeting meeting = meetingRepository.findByTenantIdAndEventRef(tenantId, eventRef).orElseThrow();
        UUID originalTopSlotId = proposedSlotRepository.findByMeetingIdOrderByRank(meeting.getId()).get(0).getId();

        eventPublisher.publishEvent(new WindowUpdatedEvent(
                tenantId, eventRef, "proj-1", "Sprint Review (updated)", List.of(organizerEmailOf()),
                Instant.parse("2026-08-10T09:00:00Z"), Instant.parse("2026-08-10T11:00:00Z"), 30));

        Meeting reloaded = meetingRepository.findById(meeting.getId()).orElseThrow();
        assertThat(reloaded.getTitle()).isEqualTo("Sprint Review (updated)");
        assertThat(proposedSlotRepository.findById(originalTopSlotId)).isEmpty();
        assertThat(proposedSlotRepository.findByMeetingIdOrderByRank(meeting.getId())).isNotEmpty();
    }

    // -------------------------------------------------------------------------
    // Disponibilités agrégées — conflit signalé
    // -------------------------------------------------------------------------

    @Test
    void windowCreated_participantBusyOnEveryCandidate_stillProposesSlots_flaggedConflicted() throws Exception {
        String secondEmail = "second-" + UUID.randomUUID() + "@pivot.test";
        Instant periodStart = Instant.parse("2026-08-03T09:00:00Z");
        Instant periodEnd = Instant.parse("2026-08-03T09:30:00Z");
        availabilityAdapter.registerBusyPeriod(secondEmail, periodStart, periodEnd);

        String eventRef = "evt-" + UUID.randomUUID();
        eventPublisher.publishEvent(new WindowCreatedEvent(
                tenantId, eventRef, "proj-1", "Sprint Review", List.of(organizerEmailOf(), secondEmail),
                periodStart, periodEnd, 30));

        Meeting meeting = meetingRepository.findByTenantIdAndEventRef(tenantId, eventRef).orElseThrow();
        List<ProposedSlot> slots = proposedSlotRepository.findByMeetingIdOrderByRank(meeting.getId());
        assertThat(slots).hasSize(1);
        assertThat(slots.get(0).isHasConflict()).isTrue();
        assertThat(slots.get(0).getConflictReason()).contains("1/2");
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private String organizerEmailOf() {
        return organizerEmail;
    }

    private String publishWindowCreated(final List<String> participants) {
        String eventRef = "evt-" + UUID.randomUUID();
        eventPublisher.publishEvent(buildWindowCreatedEvent(eventRef, participants));
        return eventRef;
    }

    private WindowCreatedEvent buildWindowCreatedEvent(final String eventRef, final List<String> participants) {
        return new WindowCreatedEvent(
                tenantId, eventRef, "proj-1", "Sprint Review", participants,
                Instant.parse("2026-08-03T09:00:00Z"), Instant.parse("2026-08-03T11:00:00Z"), 30);
    }

    /**
     * Test-only recording configuration capturing the in-process events {@link BookingService}
     * publishes, so their publication can be asserted directly (US12.4.1 "Confirmation → CONFIRMED
     * + bus" / "cohérence window.updated/deleted" ACs).
     *
     * <p>Each event type gets its own concrete {@link ApplicationListener} bean (rather than a
     * single generic {@code List<T>} bean autowired by field) — Spring's collection-injection
     * support for {@code @Autowired List<T>} aggregates beans of element type {@code T}, not a
     * bean whose own type happens to be {@code List<T>}; using distinct recorder types avoids
     * that ambiguity entirely and keeps each recorder unambiguously type-matchable.
     */
    @TestConfiguration
    static class EventRecordingConfig {

        @Bean
        BookingConfirmedRecorder bookingConfirmedRecorder() {
            return new BookingConfirmedRecorder();
        }

        @Bean
        InvitationRecorder invitationRecorder() {
            return new InvitationRecorder();
        }

        @Bean
        RescheduleRecorder rescheduleRecorder() {
            return new RescheduleRecorder();
        }
    }

    /** Captures every {@link BookingConfirmedEvent} published during a test. */
    static class BookingConfirmedRecorder implements ApplicationListener<BookingConfirmedEvent> {

        private final List<BookingConfirmedEvent> events = new CopyOnWriteArrayList<>();

        @Override
        public void onApplicationEvent(final BookingConfirmedEvent event) {
            events.add(event);
        }
    }

    /** Captures every {@link MeetingInvitationsSentEvent} published during a test. */
    static class InvitationRecorder implements ApplicationListener<MeetingInvitationsSentEvent> {

        private final List<MeetingInvitationsSentEvent> events = new CopyOnWriteArrayList<>();

        @Override
        public void onApplicationEvent(final MeetingInvitationsSentEvent event) {
            events.add(event);
        }
    }

    /** Captures every {@link RescheduleRequestedEvent} published during a test. */
    static class RescheduleRecorder implements ApplicationListener<RescheduleRequestedEvent> {

        private final List<RescheduleRequestedEvent> events = new CopyOnWriteArrayList<>();

        @Override
        public void onApplicationEvent(final RescheduleRequestedEvent event) {
            events.add(event);
        }
    }
}

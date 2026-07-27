package fr.pivot.collaboratif.meeting.kpi;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;

/**
 * Unit tests for {@link MeetopsKpiEventPublisher} (EN12.3) — verifies exactly one {@link
 * MeetopsKpiUpdatedEvent} per {@link MeetopsKpiDefinition} is published per call, each carrying
 * the given tenant/team/occurredAt, mirroring {@code
 * fr.pivot.collaboratif.session.kpi.SessionKpiEventPublisher}'s own test shape (implicit — that
 * class has no dedicated unit test file, exercised only transitively; this class adds one since
 * EN12.3's own AC explicitly requires proving the event is published).
 */
@ExtendWith(MockitoExtension.class)
class MeetopsKpiEventPublisherTest {

    private static final Long TENANT_ID = 100L;
    private static final Long TEAM_ID = 7L;
    private static final Instant OCCURRED_AT = Instant.parse("2026-08-01T10:00:00Z");

    @Mock
    private ApplicationEventPublisher eventPublisher;

    private MeetopsKpiEventPublisher publisher;

    @BeforeEach
    void setUp() {
        publisher = new MeetopsKpiEventPublisher(eventPublisher);
    }

    @Test
    void publishRecalculation_publishesOneEventPerDefinition() {
        publisher.publishRecalculation(TENANT_ID, TEAM_ID, OCCURRED_AT);

        ArgumentCaptor<MeetopsKpiUpdatedEvent> captor = ArgumentCaptor.forClass(MeetopsKpiUpdatedEvent.class);
        verify(eventPublisher, org.mockito.Mockito.times(MeetopsKpiDefinition.values().length))
                .publishEvent(captor.capture());

        List<MeetopsKpiUpdatedEvent> events = captor.getAllValues();
        assertThat(events).hasSize(5);
        assertThat(events).extracting(MeetopsKpiUpdatedEvent::kpiKey).containsExactlyInAnyOrder(
                "meetops.meetings_run", "meetops.participation_rate", "meetops.action_completion_rate",
                "meetops.agenda_adherence", "meetops.minutes_shared_rate");
        assertThat(events).allSatisfy(event -> {
            assertThat(event.tenantId()).isEqualTo(TENANT_ID);
            assertThat(event.teamId()).isEqualTo(TEAM_ID);
            assertThat(event.occurredAt()).isEqualTo(OCCURRED_AT);
        });
    }

    @Test
    void publishRecalculation_withNullTeamId_stillPublishesForATeamlessMeeting() {
        publisher.publishRecalculation(TENANT_ID, null, OCCURRED_AT);

        ArgumentCaptor<MeetopsKpiUpdatedEvent> captor = ArgumentCaptor.forClass(MeetopsKpiUpdatedEvent.class);
        verify(eventPublisher, org.mockito.Mockito.times(5)).publishEvent(captor.capture());
        assertThat(captor.getAllValues()).allSatisfy(event -> assertThat(event.teamId()).isNull());
    }
}

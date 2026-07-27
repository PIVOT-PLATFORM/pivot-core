package fr.pivot.collaboratif.meeting.ws;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link MeetingDestinations}'s destination-building and parsing helpers
 * (US12.2.1) — mirrors {@code fr.pivot.collaboratif.session.ws.SessionDestinationsTest}'s
 * coverage shape; the parsing logic is the sole gate deciding whether a STOMP frame is even
 * considered part of this channel by {@link MeetingChannelInterceptor}.
 */
class MeetingDestinationsTest {

    private static final UUID MEETING_ID = UUID.randomUUID();

    @Test
    void topicForBuildsTheFullBrokerTopic() {
        assertThat(MeetingDestinations.topicFor(MEETING_ID))
                .isEqualTo("/topic/collaboratif/meeting/" + MEETING_ID);
    }

    @Test
    void meetingIdFromParsesATopicDestinationWithNoTrailingSegment() {
        String destination = "/topic/collaboratif/meeting/" + MEETING_ID;

        assertThat(MeetingDestinations.meetingIdFrom(destination)).isEqualTo(MEETING_ID);
    }

    @Test
    void meetingIdFromParsesATopicDestinationWithATrailingSegment() {
        String destination = "/topic/collaboratif/meeting/" + MEETING_ID + "/timer";

        assertThat(MeetingDestinations.meetingIdFrom(destination)).isEqualTo(MEETING_ID);
    }

    @Test
    void meetingIdFromParsesAnAppDestination() {
        String destination = "/app/collaboratif/meeting/" + MEETING_ID + "/next";

        assertThat(MeetingDestinations.meetingIdFrom(destination)).isEqualTo(MEETING_ID);
    }

    @Test
    void meetingIdFromReturnsNullForANullDestination() {
        assertThat(MeetingDestinations.meetingIdFrom(null)).isNull();
    }

    @Test
    void meetingIdFromReturnsNullForADestinationOutsideThisChannel() {
        assertThat(MeetingDestinations.meetingIdFrom("/topic/collaboratif/session/" + MEETING_ID)).isNull();
    }

    @Test
    void meetingIdFromReturnsNullWhenTheIdSegmentIsNotAValidUuid() {
        assertThat(MeetingDestinations.meetingIdFrom("/topic/collaboratif/meeting/not-a-uuid")).isNull();
    }
}

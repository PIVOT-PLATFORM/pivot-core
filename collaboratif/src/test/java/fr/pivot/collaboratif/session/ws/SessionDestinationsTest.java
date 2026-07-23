package fr.pivot.collaboratif.session.ws;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link SessionDestinations}'s destination-building and parsing helpers
 * (US19.1.2/US19.2.1) — no test coverage existed before this PR despite the parsing logic
 * ({@link SessionDestinations#sessionIdFrom(String)}) being the sole gate deciding whether a
 * STOMP frame is even considered part of this channel by {@link SessionChannelInterceptor}.
 */
class SessionDestinationsTest {

    private static final UUID SESSION_ID = UUID.randomUUID();

    @Test
    void topicForBuildsTheFullBrokerTopic() {
        assertThat(SessionDestinations.topicFor(SESSION_ID))
                .isEqualTo("/topic/collaboratif/session/" + SESSION_ID);
    }

    @Test
    void sessionIdFromParsesATopicDestinationWithNoTrailingSegment() {
        String destination = "/topic/collaboratif/session/" + SESSION_ID;

        assertThat(SessionDestinations.sessionIdFrom(destination)).isEqualTo(SESSION_ID);
    }

    @Test
    void sessionIdFromParsesATopicDestinationWithATrailingSegment() {
        String destination = "/topic/collaboratif/session/" + SESSION_ID + "/poll";

        assertThat(SessionDestinations.sessionIdFrom(destination)).isEqualTo(SESSION_ID);
    }

    @Test
    void sessionIdFromParsesAnAppDestination() {
        String destination = "/app/collaboratif/session/" + SESSION_ID + "/vote";

        assertThat(SessionDestinations.sessionIdFrom(destination)).isEqualTo(SESSION_ID);
    }

    @Test
    void sessionIdFromReturnsNullForANullDestination() {
        assertThat(SessionDestinations.sessionIdFrom(null)).isNull();
    }

    @Test
    void sessionIdFromReturnsNullForADestinationOutsideThisChannel() {
        assertThat(SessionDestinations.sessionIdFrom("/topic/whiteboard/" + SESSION_ID)).isNull();
    }

    @Test
    void sessionIdFromReturnsNullWhenTheIdSegmentIsNotAValidUuid() {
        assertThat(SessionDestinations.sessionIdFrom("/topic/collaboratif/session/not-a-uuid")).isNull();
    }
}

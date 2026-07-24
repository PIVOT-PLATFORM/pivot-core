package fr.pivot.collaboratif.session.ws;

import fr.pivot.collaboratif.session.Participant;
import fr.pivot.collaboratif.session.ParticipantRepository;
import fr.pivot.collaboratif.session.Session;
import fr.pivot.collaboratif.session.SessionRepository;
import fr.pivot.collaboratif.session.SessionStatus;
import fr.pivot.collaboratif.session.SessionType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.security.Principal;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link SessionGuestPrincipalResolver} (US19.2.1) — no test coverage existed for
 * this class before this PR. Covers the resolver's three rejection paths (unknown token, session
 * gone, session completed) plus the success path, since a bug in any rejection branch here would
 * let a stale or foreign guest token authenticate a STOMP {@code CONNECT} frame.
 */
@ExtendWith(MockitoExtension.class)
class SessionGuestPrincipalResolverTest {

    private static final String GUEST_TOKEN = "guest-token-123";

    @Mock
    private ParticipantRepository participantRepository;
    @Mock
    private SessionRepository sessionRepository;

    private SessionGuestPrincipalResolver resolver;

    @BeforeEach
    void setUp() {
        resolver = new SessionGuestPrincipalResolver(participantRepository, sessionRepository);
    }

    private Participant participant(final UUID sessionId) {
        Participant participant = new Participant(sessionId, null, GUEST_TOKEN, "Guest", Instant.now());
        ReflectionTestUtils.setField(participant, "id", UUID.randomUUID());
        return participant;
    }

    private Session session(final UUID id, final SessionStatus status) {
        Session session = new Session(1L, null, "T", SessionType.WORDCLOUD, "ABCDEF", "{}", 10L, Instant.now());
        ReflectionTestUtils.setField(session, "id", id);
        session.setStatus(status);
        return session;
    }

    @Test
    void resolveGuestReturnsEmptyForAnUnknownToken() {
        when(participantRepository.findByGuestToken(GUEST_TOKEN)).thenReturn(Optional.empty());

        Optional<Principal> resolved = resolver.resolveGuest(GUEST_TOKEN);

        assertThat(resolved).isEmpty();
    }

    @Test
    void resolveGuestReturnsEmptyWhenTheParticipantsSessionNoLongerExists() {
        UUID sessionId = UUID.randomUUID();
        when(participantRepository.findByGuestToken(GUEST_TOKEN)).thenReturn(Optional.of(participant(sessionId)));
        when(sessionRepository.findById(sessionId)).thenReturn(Optional.empty());

        Optional<Principal> resolved = resolver.resolveGuest(GUEST_TOKEN);

        assertThat(resolved).isEmpty();
    }

    @Test
    void resolveGuestReturnsEmptyWhenTheSessionHasCompleted() {
        UUID sessionId = UUID.randomUUID();
        when(participantRepository.findByGuestToken(GUEST_TOKEN)).thenReturn(Optional.of(participant(sessionId)));
        when(sessionRepository.findById(sessionId)).thenReturn(Optional.of(session(sessionId, SessionStatus.COMPLETED)));

        Optional<Principal> resolved = resolver.resolveGuest(GUEST_TOKEN);

        assertThat(resolved).isEmpty();
    }

    @Test
    void resolveGuestReturnsAScopedPrincipalForALiveSessionsGuest() {
        UUID sessionId = UUID.randomUUID();
        Participant participant = participant(sessionId);
        when(participantRepository.findByGuestToken(GUEST_TOKEN)).thenReturn(Optional.of(participant));
        when(sessionRepository.findById(sessionId)).thenReturn(Optional.of(session(sessionId, SessionStatus.LIVE)));

        Optional<Principal> resolved = resolver.resolveGuest(GUEST_TOKEN);

        assertThat(resolved).isPresent();
        assertThat(resolved.get()).isInstanceOf(SessionGuestPrincipal.class);
        SessionGuestPrincipal principal = (SessionGuestPrincipal) resolved.get();
        assertThat(principal.sessionId()).isEqualTo(sessionId);
        assertThat(principal.participantId()).isEqualTo(participant.getId());
    }

    @Test
    void resolveGuestAlsoSucceedsForADraftSession() {
        UUID sessionId = UUID.randomUUID();
        Participant participant = participant(sessionId);
        when(participantRepository.findByGuestToken(GUEST_TOKEN)).thenReturn(Optional.of(participant));
        when(sessionRepository.findById(sessionId)).thenReturn(Optional.of(session(sessionId, SessionStatus.DRAFT)));

        Optional<Principal> resolved = resolver.resolveGuest(GUEST_TOKEN);

        assertThat(resolved).isPresent();
    }
}

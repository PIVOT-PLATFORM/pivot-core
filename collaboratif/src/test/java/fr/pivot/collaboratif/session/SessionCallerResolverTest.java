package fr.pivot.collaboratif.session;

import fr.pivot.collaboratif.exception.SessionGuestExpiredException;
import fr.pivot.collaboratif.exception.SessionNotFoundException;
import fr.pivot.core.auth.AuthenticatedPrincipal;
import fr.pivot.core.auth.AuthenticatedPrincipalResolver;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SessionCallerResolverTest {

    @Mock
    private AuthenticatedPrincipalResolver principalResolver;
    @Mock
    private ParticipantRepository participantRepository;
    @Mock
    private HttpServletRequest request;

    private SessionCallerResolver resolver;

    @BeforeEach
    void setUp() {
        resolver = new SessionCallerResolver(principalResolver, participantRepository);
    }

    @Test
    void resolveParticipantIdUsesTheBearerTokenWhenPresent() {
        UUID sessionId = UUID.randomUUID();
        Participant participant = new Participant(sessionId, 10L, null, "Alice", Instant.now());
        when(request.getHeader("Authorization")).thenReturn("Bearer raw-token");
        when(principalResolver.resolve("raw-token")).thenReturn(Optional.of(new AuthenticatedPrincipal(10L, 1L, "ROLE_USER")));
        when(participantRepository.findBySessionIdAndUserId(sessionId, 10L)).thenReturn(Optional.of(participant));

        UUID resolved = resolver.resolveParticipantId(request, sessionId);

        assertThat(resolved).isEqualTo(participant.getId());
    }

    @Test
    void resolveParticipantIdRejectsAnAuthenticatedUserWhoNeverJoinedThisSession() {
        UUID sessionId = UUID.randomUUID();
        when(request.getHeader("Authorization")).thenReturn("Bearer raw-token");
        when(principalResolver.resolve("raw-token")).thenReturn(Optional.of(new AuthenticatedPrincipal(10L, 1L, "ROLE_USER")));
        when(participantRepository.findBySessionIdAndUserId(sessionId, 10L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> resolver.resolveParticipantId(request, sessionId))
                .isInstanceOf(SessionNotFoundException.class);
    }

    @Test
    void resolveParticipantIdFallsBackToGuestTokenWhenNoBearerIsPresent() {
        UUID sessionId = UUID.randomUUID();
        Participant participant = new Participant(sessionId, null, "guest-token", "Bob", Instant.now());
        when(request.getHeader("Authorization")).thenReturn(null);
        when(request.getHeader("X-Guest-Token")).thenReturn("guest-token");
        when(participantRepository.findByGuestToken("guest-token")).thenReturn(Optional.of(participant));

        UUID resolved = resolver.resolveParticipantId(request, sessionId);

        assertThat(resolved).isEqualTo(participant.getId());
    }

    @Test
    void resolveParticipantIdRejectsAGuestTokenScopedToAnotherSession() {
        UUID sessionId = UUID.randomUUID();
        UUID otherSessionId = UUID.randomUUID();
        Participant participant = new Participant(otherSessionId, null, "guest-token", "Bob", Instant.now());
        when(request.getHeader("Authorization")).thenReturn(null);
        when(request.getHeader("X-Guest-Token")).thenReturn("guest-token");
        when(participantRepository.findByGuestToken("guest-token")).thenReturn(Optional.of(participant));

        assertThatThrownBy(() -> resolver.resolveParticipantId(request, sessionId))
                .isInstanceOf(SessionGuestExpiredException.class);
    }

    @Test
    void resolveParticipantIdRejectsWhenNeitherCredentialIsPresent() {
        UUID sessionId = UUID.randomUUID();
        when(request.getHeader("Authorization")).thenReturn(null);
        when(request.getHeader("X-Guest-Token")).thenReturn(null);

        assertThatThrownBy(() -> resolver.resolveParticipantId(request, sessionId))
                .isInstanceOf(SessionGuestExpiredException.class);
    }

    @Test
    void resolveOptionalPrincipalReturnsEmptyWithNoAuthorizationHeader() {
        when(request.getHeader("Authorization")).thenReturn(null);

        assertThat(resolver.resolveOptionalPrincipal(request)).isEmpty();
    }
}

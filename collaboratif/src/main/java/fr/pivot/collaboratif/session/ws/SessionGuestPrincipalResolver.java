package fr.pivot.collaboratif.session.ws;

import fr.pivot.collaboratif.session.Participant;
import fr.pivot.collaboratif.session.ParticipantRepository;
import fr.pivot.collaboratif.session.Session;
import fr.pivot.collaboratif.session.SessionRepository;
import fr.pivot.collaboratif.session.SessionStatus;
import fr.pivot.collaboratif.whiteboard.ws.GuestPrincipalResolver;
import org.springframework.stereotype.Component;

import java.security.Principal;
import java.util.Optional;

/**
 * Resolves a Module Session {@code guestToken} (US19.2.1) into a {@link SessionGuestPrincipal}
 * for STOMP {@code CONNECT} authentication.
 */
@Component
public class SessionGuestPrincipalResolver implements GuestPrincipalResolver {

    private final ParticipantRepository participantRepository;
    private final SessionRepository sessionRepository;

    /**
     * Creates the resolver with its required dependencies.
     *
     * @param participantRepository repository used to look up the guest's participant row
     * @param sessionRepository     repository used to reject tokens for a COMPLETED session
     */
    public SessionGuestPrincipalResolver(
            final ParticipantRepository participantRepository, final SessionRepository sessionRepository) {
        this.participantRepository = participantRepository;
        this.sessionRepository = sessionRepository;
    }

    /**
     * Resolves the guest token, rejecting unknown tokens and tokens whose session has since
     * completed.
     *
     * @param guestToken the raw token presented in the {@code X-Guest-Token} native STOMP header
     * @return the resolved principal, or empty if the token is unknown/expired
     */
    @Override
    public Optional<Principal> resolveGuest(final String guestToken) {
        Optional<Participant> participant = participantRepository.findByGuestToken(guestToken);
        if (participant.isEmpty()) {
            return Optional.empty();
        }
        Optional<Session> session = sessionRepository.findById(participant.get().getSessionId());
        if (session.isEmpty() || session.get().getStatus() == SessionStatus.COMPLETED) {
            return Optional.empty();
        }
        return Optional.of(new SessionGuestPrincipal(participant.get().getSessionId(), participant.get().getId()));
    }
}

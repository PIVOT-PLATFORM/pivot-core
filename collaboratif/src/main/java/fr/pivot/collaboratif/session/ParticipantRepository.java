package fr.pivot.collaboratif.session;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Repository for {@link Participant}.
 */
public interface ParticipantRepository extends JpaRepository<Participant, UUID> {

    /**
     * Lists participants of a session, oldest joined first.
     *
     * @param sessionId the owning session's UUID
     * @return participants, oldest joined first
     */
    List<Participant> findAllBySessionIdOrderByJoinedAtAsc(UUID sessionId);

    /**
     * Resolves a participant by session and id together — used to enforce that a guest token
     * only grants access within the exact session it was issued for.
     *
     * @param sessionId     the session UUID
     * @param participantId the participant UUID
     * @return the participant, if it belongs to that session
     */
    Optional<Participant> findByIdAndSessionId(UUID participantId, UUID sessionId);

    /**
     * Resolves a participant by their sealed guest token.
     *
     * @param guestToken the opaque guest token
     * @return the participant, if the token is known
     */
    Optional<Participant> findByGuestToken(String guestToken);

    /**
     * Counts participants of a session.
     *
     * @param sessionId the owning session's UUID
     * @return the participant count
     */
    long countBySessionId(UUID sessionId);

    /**
     * Checks whether an authenticated user has joined a session as a participant — used for WS
     * SUBSCRIBE authorization (US19.1.2 EN19.2).
     *
     * @param sessionId the owning session's UUID
     * @param userId    the authenticated user's id
     * @return {@code true} if a participant row exists for that (session, user) pair
     */
    boolean existsBySessionIdAndUserId(UUID sessionId, Long userId);

    /**
     * Resolves an authenticated caller's own participant row within a session — used to identify
     * the acting participant on activity write endpoints (POLL vote, WORDCLOUD submission).
     *
     * @param sessionId the owning session's UUID
     * @param userId    the authenticated user's id
     * @return the participant, if the user has joined this session
     */
    Optional<Participant> findBySessionIdAndUserId(UUID sessionId, Long userId);
}

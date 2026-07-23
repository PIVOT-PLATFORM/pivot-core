package fr.pivot.collaboratif.session.dto;

import tools.jackson.databind.JsonNode;
import fr.pivot.collaboratif.session.SessionStatus;
import fr.pivot.collaboratif.session.SessionType;

import java.time.Instant;
import java.util.UUID;

/**
 * Participant-safe API response shape for a session (US19.2.2) — returned by the
 * guest-accessible session-state read used by the participant view to reload session state on
 * join and again on every STOMP reconnect (US19.2.2's reconnect AC).
 *
 * <p>Deliberately a narrower shape than {@link SessionResponse} (returned by the
 * facilitator-only, bearer-only {@code GET /sessions/{id}}):
 * <ul>
 *   <li>omits {@code joinCode} — no longer needed once a participant has already joined, and
 *       re-serving it on every reload needlessly widens who can hand the code out further;</li>
 *   <li>omits {@code createdAt}/{@code teamId} — internal bookkeeping/tenant-organization detail
 *       with no participant-facing use.</li>
 * </ul>
 *
 * <p>Never carries other participants' identities, nor POLL vote tallies — live results remain
 * served exclusively through the existing hidden-results-aware WS broadcast
 * ({@code PollActivityService#getResults}/{@code PollUpdatedEvent}), never duplicated here. Only
 * {@code config} is included, which is the static, type-dependent setup blob captured once at
 * session creation (e.g. POLL's {@code question}/{@code options}, WORDCLOUD's
 * {@code maxWordsPerParticipant}) — it is never mutated after creation, so it cannot leak a
 * facilitator's live hide/show-results state.
 *
 * @param id               session id
 * @param title            session title
 * @param type             fixed activity type
 * @param status           lifecycle status
 * @param config           type-dependent configuration, as submitted at creation time
 * @param participantCount current number of participants
 * @param startedAt        first-LIVE timestamp, or {@code null}
 * @param endedAt          completion timestamp, or {@code null}
 */
public record ParticipantSessionResponse(
        UUID id,
        String title,
        SessionType type,
        SessionStatus status,
        JsonNode config,
        long participantCount,
        Instant startedAt,
        Instant endedAt) {
}

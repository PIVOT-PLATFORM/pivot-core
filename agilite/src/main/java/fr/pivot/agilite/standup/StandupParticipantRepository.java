package fr.pivot.agilite.standup;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Spring Data JPA repository for {@link StandupParticipant} entities (US10.1.1).
 */
public interface StandupParticipantRepository extends JpaRepository<StandupParticipant, UUID> {

    /**
     * Finds every participant of a session, in speaking-turn order — a direct top-level query
     * (unlike {@code StandupSession#getParticipants()}, this does not require the caller to hold
     * a live persistence context for the owning session).
     *
     * @param sessionId the owning session's id
     * @return the session's participants, {@code participantOrder} ascending
     */
    List<StandupParticipant> findBySessionIdOrderByParticipantOrderAsc(UUID sessionId);

    /**
     * Atomically finishes a currently-{@code SPEAKING} participant, but only if it is still
     * {@code SPEAKING} at the moment this {@code UPDATE} executes — the sole write path used by
     * {@code next}/{@code skip}/the US10.2.1 auto-expiry scheduler (US10.1.2 AC: a double {@code
     * next} — double-click, two animator tabs — must never advance the turn twice).
     *
     * <p>Race-free by construction, same rationale as {@code
     * RetroVoteBalanceRepository#incrementIfAvailable}: PostgreSQL's row-level lock taken during
     * the {@code UPDATE} itself makes this safe under genuine concurrent contention with no
     * {@code @Version}/optimistic-retry loop needed. {@code clearAutomatically}/{@code
     * flushAutomatically} ensure the caller's subsequent repository reads (re-loading the session
     * to find the next {@code WAITING} participant) see this write, not a stale first-level-cache
     * entity.
     *
     * @param participantId the currently-speaking participant to finish
     * @param newStatus     the terminal status to apply — {@link StandupParticipantStatus#DONE}
     *                      for a normal rotation, {@link StandupParticipantStatus#SKIPPED} for a
     *                      facilitator skip (US10.2.2)
     * @param now           the transition timestamp, written to {@code doneSpeaking}
     * @return {@code 1} if the transition was applied, {@code 0} if the participant was no
     *     longer {@code SPEAKING} (already advanced by a concurrent call — a benign no-op)
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE StandupParticipant p SET p.status = :newStatus, p.doneSpeaking = :now "
            + "WHERE p.id = :participantId AND p.status = fr.pivot.agilite.standup.StandupParticipantStatus.SPEAKING")
    int finishIfSpeaking(
            @Param("participantId") UUID participantId,
            @Param("newStatus") StandupParticipantStatus newStatus,
            @Param("now") Instant now);

    /**
     * Lightweight projection of every {@link StandupParticipantStatus#SPEAKING} participant of a
     * {@link StandupSessionStatus#RUNNING} session — used by {@link StandupTimerScheduler} to
     * compute each candidate's speaking deadline without loading the full entity graph (deferred
     * initialization of {@code StandupSession.participants}, a {@code LAZY} collection, would
     * otherwise require its own persistence context/transaction at the scheduler call site; this
     * plain scalar projection query needs neither).
     *
     * @return one row per currently-speaking participant of a running session
     */
    @Query("SELECT p.session.id AS sessionId, p.speakingAt AS speakingAt, "
            + "p.session.timePerPersonSeconds AS timePerPersonSeconds, p.extraSeconds AS extraSeconds "
            + "FROM StandupParticipant p "
            + "WHERE p.status = fr.pivot.agilite.standup.StandupParticipantStatus.SPEAKING "
            + "AND p.session.status = fr.pivot.agilite.standup.StandupSessionStatus.RUNNING")
    List<SpeakingDeadlineRow> findRunningSpeakingDeadlines();

    /**
     * Projection row for {@link #findRunningSpeakingDeadlines}.
     */
    interface SpeakingDeadlineRow {

        /**
         * Returns the session this speaking participant belongs to.
         *
         * @return the session id
         */
        UUID getSessionId();

        /**
         * Returns the timestamp this participant started speaking.
         *
         * @return the speakingAt instant
         */
        Instant getSpeakingAt();

        /**
         * Returns the session's configured speaking time per participant, in seconds.
         *
         * @return the time per person, in seconds
         */
        int getTimePerPersonSeconds();

        /**
         * Returns this participant's cumulative extra seconds granted via {@code extend}.
         *
         * @return the extra seconds
         */
        int getExtraSeconds();
    }

    /**
     * Aggregated per-participant speaking statistics for every {@link
     * StandupSessionStatus#DONE} session of a team, started within the given window (US10.3.1) —
     * a single grouped SQL query rather than an in-memory load of every session/participant row,
     * mirroring {@code WheelDrawService#listDraws}'s query-based (not in-memory-computed)
     * approach. Native query: {@code EXTRACT(EPOCH FROM ...)} duration arithmetic has no portable
     * JPQL equivalent, same rationale as {@code RetroVoteBalanceRepository}'s native atomic
     * updates.
     *
     * <p>A {@link StandupParticipantStatus#SKIPPED} participant always contributes {@code 0} to
     * {@code totalSpeakingSeconds} (US10.2.2 AC) — never derived from {@code doneSpeaking -
     * speakingAt}, which would be non-zero since both timestamps are stamped at skip time.
     *
     * @param teamId   the team's {@code public.teams.id}
     * @param tenantId the expected tenant's {@code public.tenants.id}
     * @param from     inclusive lower bound on the session's {@code startedAt}
     * @param to       inclusive upper bound on the session's {@code startedAt}
     * @return one row per distinct participant name, ordered by total speaking time descending
     */
    @Query(value = "SELECT p.name AS name, "
            + "COUNT(DISTINCT p.session_id) AS sessionCount, "
            + "COALESCE(SUM(CASE WHEN p.status = 'SKIPPED' THEN 0 "
            + "  WHEN p.done_speaking IS NOT NULL AND p.speaking_at IS NOT NULL "
            + "    THEN EXTRACT(EPOCH FROM (p.done_speaking - p.speaking_at))::bigint "
            + "  ELSE 0 END), 0) AS totalSpeakingSeconds "
            + "FROM agilite.standup_participant p "
            + "JOIN agilite.standup_session s ON s.id = p.session_id "
            + "WHERE s.team_id = :teamId AND s.tenant_id = :tenantId AND s.status = 'DONE' "
            + "AND s.started_at BETWEEN :from AND :to "
            + "GROUP BY p.name "
            + "ORDER BY totalSpeakingSeconds DESC",
            nativeQuery = true)
    List<ParticipantStatRow> aggregateSpeakingStats(
            @Param("teamId") Long teamId,
            @Param("tenantId") Long tenantId,
            @Param("from") Instant from,
            @Param("to") Instant to);

    /**
     * Projection row for {@link #aggregateSpeakingStats}.
     */
    interface ParticipantStatRow {

        /**
         * Returns the participant's denormalized display name.
         *
         * @return the name
         */
        String getName();

        /**
         * Returns the number of distinct sessions in the window where this name appears.
         *
         * @return the session count
         */
        long getSessionCount();

        /**
         * Returns the total speaking time across every session in the window, in seconds.
         *
         * @return the total speaking seconds
         */
        long getTotalSpeakingSeconds();
    }
}

package fr.pivot.collaboratif.session.kpi;

import fr.pivot.collaboratif.session.Session;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import java.util.UUID;

/**
 * Read-only aggregate query backing every {@link SessionKpiDefinition} (EN19.4). Deliberately a
 * bare {@link Repository} marker (not {@code JpaRepository}) — this is not a CRUD repository for
 * {@link Session}, only a vehicle for the one native aggregate query below.
 */
public interface SessionKpiRepository extends Repository<Session, UUID> {

    /**
     * Computes all five Session live KPIs in one query, scoped to a tenant and optionally
     * narrowed to one of its teams.
     *
     * <p><strong>Population.</strong> Only sessions that left {@code DRAFT} count (a session
     * never started never "ran"). <strong>No explicit time-period filter</strong> — the {@code
     * KpiRef} schema this enum implements (EN28.14) carries no date-range field, only a logical
     * {@code scope}; this computes an all-time aggregate for the resolved scope, the same
     * simplification {@code fr.pivot.agilite.capacity.kpi.KpiService} already makes for its own
     * KPIs.
     *
     * <p><strong>Participation.</strong> A participant "interacted" if they have at least one row
     * in the activity-type table matching whichever single {@code SessionType} that session was
     * created with (each session maps to exactly one activity type, see {@code Activity}'s
     * Javadoc) — {@code session_poll_vote}, {@code session_qa_question}, {@code
     * session_wordcloud_submission}, {@code session_brainstorm_card}, {@code
     * session_quiz_answer}, or {@code session_vote_ballot}. A plain {@code UNION ALL} is
     * correct here (not {@code UNION}): a given session only ever contributes rows to exactly one
     * of these six tables, so no cross-table duplicate {@code (session_id, participant_id)} pair
     * can occur — the {@code COUNT(DISTINCT participant_id)} in the outer grouping only needs to
     * dedupe a participant appearing more than once <em>within</em> the same table (e.g. several
     * Q&amp;A questions from the same author).
     *
     * @param tenantId the caller's tenant — always required, never optional (multi-tenant
     *                  isolation)
     * @param teamId    the team to narrow to, or {@code null} for a tenant-wide aggregate
     * @return the five computed KPI values
     */
    @Query(value = """
            WITH scoped_sessions AS (
                SELECT id, status, team_id
                FROM collaboratif.session
                WHERE tenant_id = :tenantId
                  AND status <> 'DRAFT'
                  AND (:teamId IS NULL OR team_id = :teamId)
            ),
            participant_counts AS (
                SELECT session_id, COUNT(*) AS cnt
                FROM collaboratif.session_participant
                GROUP BY session_id
            ),
            interactions AS (
                SELECT session_id, participant_id FROM collaboratif.session_poll_vote
                UNION ALL
                SELECT session_id, participant_id FROM collaboratif.session_qa_question
                UNION ALL
                SELECT session_id, participant_id FROM collaboratif.session_wordcloud_submission
                UNION ALL
                SELECT session_id, participant_id FROM collaboratif.session_brainstorm_card
                UNION ALL
                SELECT session_id, participant_id FROM collaboratif.session_quiz_answer
                UNION ALL
                SELECT session_id, participant_id FROM collaboratif.session_vote_ballot
            ),
            interaction_counts AS (
                SELECT session_id, COUNT(DISTINCT participant_id) AS cnt
                FROM interactions
                GROUP BY session_id
            )
            SELECT
                COUNT(DISTINCT ss.id) AS sessionsRun,
                COUNT(DISTINCT sa.id) AS activitiesRun,
                COALESCE(AVG(COALESCE(pc.cnt, 0)), 0)::float8 AS avgParticipants,
                COALESCE(SUM(ic.cnt)::float8 / NULLIF(SUM(pc.cnt), 0), 0) * 100 AS participationRate,
                COALESCE(
                    SUM(CASE WHEN ss.status = 'COMPLETED' THEN 1 ELSE 0 END)::float8
                        / NULLIF(COUNT(DISTINCT ss.id), 0),
                    0
                ) * 100 AS completionRate
            FROM scoped_sessions ss
            LEFT JOIN collaboratif.session_activity sa ON sa.session_id = ss.id
            LEFT JOIN participant_counts pc ON pc.session_id = ss.id
            LEFT JOIN interaction_counts ic ON ic.session_id = ss.id
            """, nativeQuery = true)
    SessionKpiAggregate aggregate(@Param("tenantId") Long tenantId, @Param("teamId") Long teamId);

    /**
     * Checks that a {@code teamId} scope belongs to the caller's own tenant, without going
     * through {@code fr.pivot.core.team.TeamRepository}.
     *
     * <p>{@code fr.pivot.core.team.Team}'s {@code @Table} carries no explicit {@code schema =
     * "public"} — resolving it via JPA from inside this module's own persistence context resolves
     * against {@code collaboratif}'s own {@code hibernate.default_schema} instead of {@code
     * public}, failing with {@code relation "collaboratif.teams" does not exist} (discovered by
     * this enabler's own integration test — every existing collaboratif caller of team data goes
     * through {@code TeamMemberRepository} instead, whose narrower query surface apparently never
     * exercised this same resolution path against a real database). A native, explicitly
     * schema-qualified {@code public.teams} check sidesteps the defect entirely rather than fixing
     * the shared entity mapping (out of scope for a Session-live-only enabler, and a starter-wide
     * change with broader blast radius than this PR should carry).
     *
     * @param teamId   the team to check
     * @param tenantId the caller's tenant
     * @return {@code true} if {@code teamId} exists and belongs to {@code tenantId}
     */
    @Query(value = "SELECT EXISTS(SELECT 1 FROM public.teams WHERE id = :teamId AND tenant_id = :tenantId)",
            nativeQuery = true)
    boolean teamBelongsToTenant(@Param("teamId") Long teamId, @Param("tenantId") Long tenantId);
}

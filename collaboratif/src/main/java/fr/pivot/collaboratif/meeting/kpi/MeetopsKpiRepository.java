package fr.pivot.collaboratif.meeting.kpi;

import fr.pivot.collaboratif.meeting.Meeting;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import java.util.UUID;

/**
 * Read-only aggregate query backing every {@link MeetopsKpiDefinition} (EN12.3). Deliberately a
 * bare {@link Repository} marker (not {@code JpaRepository}) — this is not a CRUD repository for
 * {@link Meeting}, only a vehicle for the two native queries below, mirroring {@code
 * fr.pivot.collaboratif.session.kpi.SessionKpiRepository}'s own shape for EN19.4.
 */
public interface MeetopsKpiRepository extends Repository<Meeting, UUID> {

    /**
     * Computes all five MeetOps KPIs in one query, scoped to a tenant and optionally narrowed to
     * one of its teams.
     *
     * <p><strong>{@code meetings_run} population.</strong> A meeting counts once it has actually
     * been held — reached {@link fr.pivot.collaboratif.meeting.MeetingStatus#IN_PROGRESS} or
     * {@link fr.pivot.collaboratif.meeting.MeetingStatus#ENDED} — never {@code DRAFT} (never
     * started) nor {@code CONFIRMED} (scheduled but not yet started; see {@code MeetingStatus}'s
     * own Javadoc noting it is not yet reachable in production anyway). The Gate 1 architecture
     * note named this population "hors DRAFT/PRE_RESERVED", a status that does not exist in this
     * schema's actual {@code MeetingStatus} enum — {@code IN_PROGRESS}/{@code ENDED} is the
     * faithful equivalent of "réunions réellement tenues" against the real lifecycle.
     *
     * <p><strong>The other four KPIs are scoped to {@code ENDED} meetings only</strong> (a subset
     * of the {@code meetings_run} population) — {@code agenda_adherence} needs an item's final
     * {@code actual_seconds} (only ever set once {@code DONE}), {@code action_completion_rate}/
     * {@code minutes_shared_rate}/{@code participation_rate} are post-hoc, whole-meeting
     * completeness signals that are only meaningful once a meeting has actually concluded — an
     * {@code IN_PROGRESS} meeting's partial data would make these rates noisy mid-meeting.
     *
     * <p><strong>No explicit time-period filter</strong> — same simplification as {@code
     * SessionKpiRepository#aggregate}: the {@code KpiRef} schema (EN28.14) carries no date-range
     * field, only a logical {@code scope}, so this computes an all-time aggregate.
     *
     * <p><strong>{@code agenda_adherence}.</strong> Per {@link
     * fr.pivot.collaboratif.meeting.AgendaItem}, once {@code DONE}: {@code adherence = 100 * (1 -
     * min(1, |actual_seconds - duration_minutes*60| / (duration_minutes*60)))}, bounded to
     * {@code [0, 100]} (a wildly over- or under-run item floors at {@code 0}, never goes
     * negative), averaged across every {@code DONE} item of the scoped ended meetings.
     *
     * <p><strong>{@code action_completion_rate}.</strong> {@code 100 * count(status <> 'OPEN') /
     * count(*)} over every {@link fr.pivot.collaboratif.meeting.MeetingAction} captured during the
     * scoped ended meetings — see {@link MeetopsKpiDefinition}'s own Javadoc for why this is
     * {@code 0} on every real tenant today (no code path ever closes an action yet).
     *
     * <p><strong>{@code minutes_shared_rate}.</strong> {@code 100 * count(meetings with a frozen
     * collaboratif.meeting_report row) / count(scoped ended meetings)} — a report is frozen
     * automatically at closure ({@code MeetingReportService#freezeOnClose}), so this is {@code
     * ~100%} for every meeting closed after that feature shipped and {@code 0} only for a meeting
     * that somehow ended without ever freezing one.
     *
     * <p><strong>{@code participation_rate}.</strong> This schema has no attendance/invite log
     * (see {@link MeetopsKpiDefinition}'s own Javadoc for the full rationale) — "engaged" is
     * approximated as a distinct user who is either the {@code owner_user_id} of a captured
     * action or the {@code created_by} of a recorded decision during a given ended meeting,
     * restricted to users who are members of the scoped team (via {@code public.team_members}, so
     * an action assigned to someone outside the team never inflates the rate). Per scoped ended
     * meeting this yields an engaged-member count between {@code 0} and the team's size; the KPI
     * is {@code 100 * sum(engaged members per meeting) / (team size * count(ended meetings))} —
     * the same "sum of per-row numerator over a shared denominator" shape {@code
     * SessionKpiRepository#aggregate}'s own {@code participationRate} already uses.
     *
     * @param tenantId the caller's tenant — always required, never optional (multi-tenant
     *                 isolation)
     * @param teamId   the team to narrow to, or {@code null} for a tenant-wide aggregate (only
     *                 {@code meetings_run} supports {@code null}; the other four fields are
     *                 meaningless — and computed as {@code 0} — without a team)
     * @return the five computed KPI values
     */
    @Query(value = """
            WITH scoped_meetings AS (
                SELECT id
                FROM collaboratif.meetings
                WHERE tenant_id = :tenantId
                  AND status IN ('IN_PROGRESS', 'ENDED')
                  AND (:teamId IS NULL OR team_id = :teamId)
            ),
            ended_meetings AS (
                SELECT id
                FROM collaboratif.meetings
                WHERE tenant_id = :tenantId
                  AND status = 'ENDED'
                  AND (:teamId IS NULL OR team_id = :teamId)
            ),
            agenda_adherence_rows AS (
                SELECT
                    100.0::float8 * (1.0::float8 - LEAST(
                        1.0::float8,
                        ABS(ai.actual_seconds - ai.duration_minutes * 60)::float8
                            / (ai.duration_minutes * 60)::float8
                    )) AS adherence
                FROM collaboratif.agenda_items ai
                WHERE ai.item_status = 'DONE'
                  AND ai.actual_seconds IS NOT NULL
                  AND ai.meeting_id IN (SELECT id FROM ended_meetings)
            ),
            action_rows AS (
                SELECT CASE WHEN ma.status <> 'OPEN' THEN 1.0::float8 ELSE 0.0::float8 END AS done
                FROM collaboratif.meeting_actions ma
                WHERE ma.meeting_id IN (SELECT id FROM ended_meetings)
            ),
            report_rows AS (
                SELECT (mr.meeting_id IS NOT NULL) AS shared
                FROM ended_meetings em
                LEFT JOIN collaboratif.meeting_report mr ON mr.meeting_id = em.id
            ),
            team_member_count AS (
                SELECT COUNT(*)::float8 AS cnt FROM public.team_members WHERE team_id = :teamId
            ),
            engaged_users AS (
                SELECT meeting_id, owner_user_id AS user_id
                FROM collaboratif.meeting_actions
                WHERE owner_user_id IS NOT NULL AND meeting_id IN (SELECT id FROM ended_meetings)
                UNION
                SELECT meeting_id, created_by AS user_id
                FROM collaboratif.meeting_decisions
                WHERE created_by IS NOT NULL AND meeting_id IN (SELECT id FROM ended_meetings)
            ),
            engaged_team_members AS (
                SELECT DISTINCT eu.meeting_id, eu.user_id
                FROM engaged_users eu
                JOIN public.team_members tm ON tm.team_id = :teamId AND tm.user_id = eu.user_id
            ),
            engaged_counts AS (
                SELECT meeting_id, COUNT(*)::float8 AS cnt
                FROM engaged_team_members
                GROUP BY meeting_id
            )
            SELECT
                (SELECT COUNT(*) FROM scoped_meetings) AS meetingsRun,
                COALESCE(
                    (SELECT SUM(COALESCE(ec.cnt, 0.0::float8))
                     FROM ended_meetings em LEFT JOIN engaged_counts ec ON ec.meeting_id = em.id)
                    / NULLIF((SELECT cnt FROM team_member_count) * (SELECT COUNT(*)::float8 FROM ended_meetings), 0),
                    0
                ) * 100 AS participationRate,
                COALESCE((SELECT AVG(done) FROM action_rows), 0) * 100 AS actionCompletionRate,
                COALESCE((SELECT AVG(adherence) FROM agenda_adherence_rows), 0) AS agendaAdherence,
                COALESCE(
                    (SELECT AVG(CASE WHEN shared THEN 1.0::float8 ELSE 0.0::float8 END) FROM report_rows), 0
                ) * 100 AS minutesSharedRate
            """, nativeQuery = true)
    MeetopsKpiAggregate aggregate(@Param("tenantId") Long tenantId, @Param("teamId") Long teamId);

    /**
     * Checks that a {@code teamId} scope belongs to the caller's own tenant, without going
     * through {@code fr.pivot.core.team.TeamRepository} — copied identically from {@code
     * SessionKpiRepository#teamBelongsToTenant}, same {@code public.teams} schema-qualification
     * gotcha applies here (see that method's own Javadoc for the full explanation).
     *
     * @param teamId   the team to check
     * @param tenantId the caller's tenant
     * @return {@code true} if {@code teamId} exists and belongs to {@code tenantId}
     */
    @Query(value = "SELECT EXISTS(SELECT 1 FROM public.teams WHERE id = :teamId AND tenant_id = :tenantId)",
            nativeQuery = true)
    boolean teamBelongsToTenant(@Param("teamId") Long teamId, @Param("tenantId") Long tenantId);
}

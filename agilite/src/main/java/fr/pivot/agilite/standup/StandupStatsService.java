package fr.pivot.agilite.standup;

import fr.pivot.agilite.exception.StandupValidationException;
import fr.pivot.agilite.standup.dto.StandupParticipantStatsEntry;
import fr.pivot.agilite.standup.dto.StandupSessionStatsEntry;
import fr.pivot.agilite.standup.dto.StandupStatsResponse;
import fr.pivot.agilite.team.TeamMembershipService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;

/**
 * Business logic for standup session statistics (US10.3.1).
 */
@Service
@Transactional(readOnly = true)
public class StandupStatsService {

    /** Default lookback window when {@code from}/{@code to} are both omitted (AC: 30 days). */
    private static final int DEFAULT_LOOKBACK_DAYS = 30;

    private final StandupSessionRepository sessionRepository;
    private final StandupParticipantRepository participantRepository;
    private final TeamMembershipService teamMembershipService;
    private final Clock clock;

    /**
     * Constructs the service with all required dependencies.
     *
     * @param sessionRepository     repository for session persistence
     * @param participantRepository repository for the aggregated per-participant stats query
     * @param teamMembershipService shared team-resolution/membership-check helper
     * @param clock                 the shared clock, overridable in tests
     */
    public StandupStatsService(
            final StandupSessionRepository sessionRepository,
            final StandupParticipantRepository participantRepository,
            final TeamMembershipService teamMembershipService,
            final Clock clock) {
        this.sessionRepository = sessionRepository;
        this.participantRepository = participantRepository;
        this.teamMembershipService = teamMembershipService;
        this.clock = clock;
    }

    /**
     * Returns a team's completed-session and per-participant speaking statistics over a period
     * (US10.3.1).
     *
     * @param teamId       the team's {@code public.teams.id}
     * @param from         inclusive lower bound (calendar date), {@code null} to default to
     *                     {@code to - 30 days}
     * @param to           inclusive upper bound (calendar date), {@code null} to default to today
     * @param callerUserId the calling user's {@code public.users.id}
     * @param tenantId     the calling tenant's {@code public.tenants.id}
     * @return the team's stats for the resolved period
     * @throws fr.pivot.agilite.exception.TeamNotFoundException if the team does not exist,
     *     belongs to another tenant, or the caller is not one of its members
     * @throws StandupValidationException if {@code from} is after {@code to}
     */
    public StandupStatsResponse getStats(
            final Long teamId, final LocalDate from, final LocalDate to, final Long callerUserId, final Long tenantId) {
        teamMembershipService.resolveTeamForCaller(teamId, callerUserId, tenantId);

        LocalDate effectiveTo = to != null ? to : LocalDate.now(clock);
        LocalDate effectiveFrom = from != null ? from : effectiveTo.minusDays(DEFAULT_LOOKBACK_DAYS);
        if (effectiveFrom.isAfter(effectiveTo)) {
            throw new StandupValidationException("INVALID_DATE_RANGE", "from must not be after to");
        }

        Instant fromInstant = effectiveFrom.atStartOfDay(ZoneOffset.UTC).toInstant();
        Instant toInstant = effectiveTo.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant().minusNanos(1);

        List<StandupSessionStatsEntry> sessions = sessionRepository
                .findAllByTeamIdAndTenantIdAndStatusAndStartedAtBetweenOrderByStartedAtDesc(
                        teamId, tenantId, StandupSessionStatus.DONE, fromInstant, toInstant)
                .stream()
                .map(session -> new StandupSessionStatsEntry(
                        session.getId(),
                        session.getName(),
                        session.getStartedAt(),
                        Duration.between(session.getStartedAt(), session.getEndedAt()).getSeconds()))
                .toList();

        List<StandupParticipantStatsEntry> participants = participantRepository
                .aggregateSpeakingStats(teamId, tenantId, fromInstant, toInstant)
                .stream()
                .map(row -> new StandupParticipantStatsEntry(
                        row.getName(), row.getSessionCount(), row.getTotalSpeakingSeconds()))
                .toList();

        return new StandupStatsResponse(sessions, participants);
    }
}

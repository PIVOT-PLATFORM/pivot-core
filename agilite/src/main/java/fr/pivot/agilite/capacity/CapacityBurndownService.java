package fr.pivot.agilite.capacity;

import fr.pivot.agilite.capacity.dto.BurndownPointResponse;
import fr.pivot.agilite.capacity.dto.BurndownResponse;
import fr.pivot.agilite.exception.CapacityValidationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Business logic for a {@code SPRINT} event's daily burndown entry and derived chart (US11.4.2).
 *
 * <p>{@link Clock} is injected (never {@code LocalDate.now()} called directly) so {@link
 * #getBurndown}'s {@code stale} detection is deterministically testable — same precedent as
 * {@code fr.pivot.agilite.retro.phase.RetroPhaseScheduler}/{@code
 * fr.pivot.agilite.standup.StandupTimerScheduler}.
 */
@Service
@Transactional
public class CapacityBurndownService {

    private final CapacityEventService eventService;
    private final CapacityBurndownEntryRepository entryRepository;
    private final Clock clock;

    /**
     * Creates the service with its required dependencies.
     *
     * @param eventService    shared event access resolution
     * @param entryRepository repository for burndown entry persistence
     * @param clock           the clock "today" is resolved from, injected for testability
     */
    public CapacityBurndownService(
            final CapacityEventService eventService, final CapacityBurndownEntryRepository entryRepository, final Clock clock) {
        this.eventService = eventService;
        this.entryRepository = entryRepository;
        this.clock = clock;
    }

    /**
     * Idempotently records (inserts or replaces) a single day's points-remaining entry.
     *
     * @param eventId      the event UUID
     * @param date         the calendar date, must fall within the event's period
     * @param pointsRemaining the points remaining, must be non-negative (bean-validated upstream)
     * @param callerUserId the calling user's {@code public.users.id}
     * @param tenantId     the calling tenant's {@code public.tenants.id}
     */
    public void upsertEntry(
            final UUID eventId,
            final LocalDate date,
            final int pointsRemaining,
            final Long callerUserId,
            final Long tenantId) {
        CapacityEvent event = eventService.resolveForCaller(eventId, callerUserId, tenantId);
        if (event.getType() != CapacityEventType.SPRINT) {
            throw new CapacityValidationException(
                    "INVALID_EVENT_TYPE_FOR_BURNDOWN", "Burndown can only be recorded on SPRINT events");
        }
        if (date.isBefore(event.getStartDate()) || date.isAfter(event.getEndDate())) {
            throw new CapacityValidationException("DATE_OUTSIDE_EVENT", "date must fall within the event period");
        }
        entryRepository.findByEventIdAndDate(eventId, date)
                .ifPresentOrElse(
                        existing -> existing.setPointsRemaining(pointsRemaining),
                        () -> entryRepository.save(new CapacityBurndownEntry(eventId, date, pointsRemaining)));
    }

    /**
     * Builds the full burndown chart payload for a {@code SPRINT} event: the linear ideal curve,
     * the recorded actual entries, and the {@code atRisk}/{@code stale} flags.
     *
     * @param eventId      the event UUID
     * @param callerUserId the calling user's {@code public.users.id}
     * @param tenantId     the calling tenant's {@code public.tenants.id}
     * @return the burndown response
     */
    @Transactional(readOnly = true)
    public BurndownResponse getBurndown(final UUID eventId, final Long callerUserId, final Long tenantId) {
        CapacityEvent event = eventService.resolveForCaller(eventId, callerUserId, tenantId);
        if (event.getType() != CapacityEventType.SPRINT) {
            throw new CapacityValidationException(
                    "INVALID_EVENT_TYPE_FOR_BURNDOWN", "Burndown can only be read on SPRINT events");
        }

        List<CapacityBurndownCalculator.IdealPoint> idealPoints = CapacityBurndownCalculator.idealCurve(
                event.getStartDate(), event.getEndDate(), event.getCommittedPoints());
        List<CapacityBurndownCalculator.ActualPoint> actualPoints = entryRepository
                .findAllByEventIdOrderByDateAsc(eventId)
                .stream()
                .map(entry -> new CapacityBurndownCalculator.ActualPoint(entry.getDate(), entry.getPointsRemaining()))
                .toList();

        boolean atRisk = CapacityBurndownCalculator.isAtRisk(idealPoints, actualPoints);
        boolean stale = CapacityBurndownCalculator.isStale(actualPoints, event.getStartDate(), event.getEndDate(), clock);

        return new BurndownResponse(
                idealPoints.stream().map(point -> new BurndownPointResponse(point.date(), point.pointsRemaining())).toList(),
                actualPoints.stream()
                        .map(point -> new BurndownPointResponse(point.date(), point.pointsRemaining()))
                        .toList(),
                atRisk,
                stale);
    }
}

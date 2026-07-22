package fr.pivot.agilite.capacity;

import java.time.Clock;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Pure, persistence-free burndown curve generation and risk/staleness detection for a {@code
 * SPRINT}-typed event (US11.4.2).
 *
 * <p>Deliberately decoupled from JPA/Spring so it can be exercised in plain JUnit without a
 * database, {@link Clock} always injected rather than {@code Instant.now()}/{@code
 * LocalDate.now()} called directly — same posture/precedent as {@code
 * fr.pivot.agilite.retro.phase.RetroPhaseScheduler}/{@code
 * fr.pivot.agilite.standup.StandupTimerScheduler}.
 */
public final class CapacityBurndownCalculator {

    /** Minimum consecutive calendar days above ideal for {@link #isAtRisk} to report {@code true}. */
    private static final int AT_RISK_CONSECUTIVE_DAYS = 2;

    /** Minimum calendar days since the last entry for {@link #isStale} to report {@code true}. */
    private static final int STALE_DAYS_THRESHOLD = 3;

    private CapacityBurndownCalculator() {
    }

    /**
     * One point of the linear ideal curve.
     *
     * @param date            the calendar date, always a working day (Monday-Friday)
     * @param pointsRemaining the ideal points remaining on that date
     */
    public record IdealPoint(LocalDate date, double pointsRemaining) {
    }

    /**
     * One daily-entered actual point.
     *
     * @param date            the calendar date the entry was recorded for
     * @param pointsRemaining the points remaining as of that date, as entered
     */
    public record ActualPoint(LocalDate date, int pointsRemaining) {
    }

    /**
     * Generates the linear ideal burndown curve, one point per working day (Monday-Friday) in
     * {@code [startDate, endDate]}, decrementing from {@code committedPoints} on the first
     * working day to {@code 0} on the last.
     *
     * @param startDate       the sprint's start date
     * @param endDate         the sprint's end date
     * @param committedPoints the committed points, or {@code null} if not yet set
     * @return the ideal curve, empty if {@code committedPoints} is {@code null} or the period has
     *     no working days
     */
    public static List<IdealPoint> idealCurve(
            final LocalDate startDate, final LocalDate endDate, final Integer committedPoints) {
        if (committedPoints == null) {
            return List.of();
        }
        List<LocalDate> workingDays = new ArrayList<>();
        for (LocalDate day = startDate; !day.isAfter(endDate); day = day.plusDays(1)) {
            if (CapacityCalculator.countWorkingDays(day, day) == 1) {
                workingDays.add(day);
            }
        }
        int n = workingDays.size();
        if (n == 0) {
            return List.of();
        }
        List<IdealPoint> points = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            double remaining = n == 1 ? 0 : committedPoints * (n - 1 - i) / (double) (n - 1);
            points.add(new IdealPoint(workingDays.get(i), remaining));
        }
        return points;
    }

    /**
     * Detects whether the actual burndown has been above the ideal curve for {@value
     * #AT_RISK_CONSECUTIVE_DAYS} or more consecutive calendar days (US11.4.2).
     *
     * @param ideal  the ideal curve, as produced by {@link #idealCurve}
     * @param actual the recorded actual entries, in any order
     * @return {@code true} if two (or more) consecutive calendar dates both have an actual entry
     *     strictly above their corresponding ideal value
     */
    public static boolean isAtRisk(final List<IdealPoint> ideal, final List<ActualPoint> actual) {
        Map<LocalDate, Double> idealByDate = new HashMap<>();
        for (IdealPoint point : ideal) {
            idealByDate.put(point.date(), point.pointsRemaining());
        }
        Map<LocalDate, Integer> actualByDate = new HashMap<>();
        for (ActualPoint point : actual) {
            actualByDate.put(point.date(), point.pointsRemaining());
        }
        int consecutive = 0;
        for (LocalDate date : actualByDate.keySet().stream().sorted().toList()) {
            Double idealValue = idealByDate.get(date);
            boolean above = idealValue != null && actualByDate.get(date) > idealValue;
            consecutive = above ? consecutive + 1 : 0;
            if (consecutive >= AT_RISK_CONSECUTIVE_DAYS) {
                return true;
            }
        }
        return false;
    }

    /**
     * Detects whether the burndown is stale: the event is currently in progress, and no actual
     * entry has been recorded in the last {@value #STALE_DAYS_THRESHOLD} calendar days (or ever).
     *
     * @param actual    the recorded actual entries, in any order
     * @param startDate the event's start date
     * @param endDate   the event's end date
     * @param clock     the clock to resolve "today" from — never {@code LocalDate.now()} directly
     * @return {@code true} if the event is currently in progress and stale
     */
    public static boolean isStale(
            final List<ActualPoint> actual, final LocalDate startDate, final LocalDate endDate, final Clock clock) {
        LocalDate today = LocalDate.now(clock);
        if (today.isBefore(startDate) || today.isAfter(endDate)) {
            return false;
        }
        if (actual.isEmpty()) {
            return true;
        }
        LocalDate lastEntryDate = actual.stream().map(ActualPoint::date).max(LocalDate::compareTo).orElseThrow();
        return ChronoUnit.DAYS.between(lastEntryDate, today) >= STALE_DAYS_THRESHOLD;
    }
}

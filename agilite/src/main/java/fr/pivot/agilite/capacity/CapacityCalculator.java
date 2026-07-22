package fr.pivot.agilite.capacity;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Set;

/**
 * Pure, persistence-free computation of a capacity event's net-capacity summary (US11.1.2,
 * extended by the full F11.6 engine — US11.6.1 through US11.6.5, Sprint 21).
 *
 * <p><strong>Two summarize overloads, one meaning shift</strong>: the original 4-argument {@link
 * #summarize(LocalDate, LocalDate, List, Double)} (S20) is kept byte-for-byte for backward
 * compatibility and always returns {@code isProvisional: true} (weekends-only, no focus factor) —
 * its own Javadoc already announced "the real F11.6 engine will replace this formula, not add to
 * it." The new {@link #summarize(LocalDate, LocalDate, List, Double, Set, int)} overload IS that
 * replacement: holiday-aware working days (US11.6.1), per-member effective focus factor
 * (US11.6.2), and it lets the caller ({@code CapacitySummaryService}) decide {@code
 * isProvisional} — {@code false} once real tenant/team configuration exists, {@code true}
 * otherwise (US11.6.5 §Architecture) — rather than hardcoding it here. Deliberately decoupled
 * from JPA/Spring so it can be exercised in plain JUnit without a database — same posture as
 * {@code fr.pivot.agilite.pi.PiIterationGenerator}.
 */
public final class CapacityCalculator {

    private CapacityCalculator() {
    }

    /**
     * A single absence period, already clipped to nothing — clipping to the owning event's
     * period happens inside {@code summarize}.
     *
     * @param start absence start date (inclusive)
     * @param end   absence end date (inclusive)
     */
    public record AbsenceRange(LocalDate start, LocalDate end) {
    }

    /**
     * One team member's contribution inputs to a leaf event's capacity summary.
     *
     * @param excluded            whether this member is excluded from the calculation (US11.2.1)
     * @param availabilityPercent the member's availability percentage, {@code [10, 100]}
     * @param absences            the member's absence periods (not yet clipped to the event)
     * @param focusFactorPercent  the member's own focus-factor override, or {@code null} to defer
     *                            to the event-level {@code eventFocusFactorPercent} (US11.6.2) —
     *                            ignored by the S20 4-argument {@code summarize} overload
     */
    public record MemberInput(
            boolean excluded, int availabilityPercent, List<AbsenceRange> absences, Integer focusFactorPercent) {
    }

    /**
     * The computed capacity summary for one event (leaf or aggregated).
     *
     * @param durationDays      total calendar days in the event's period, inclusive
     * @param workingDays       working days in the event's period, inclusive (Monday-Friday only
     *                          for the S20 overload; also excluding tenant holidays for the full
     *                          F11.6 overload)
     * @param memberCount       number of non-excluded members contributing (0 for an empty
     *                          leaf's roster or a childless PI Planning event)
     * @param totalAbsenceDays  sum of each contributing member's working-day absence overlap
     * @param netCapacityDays   the net capacity, in person-days (focus-factor-adjusted for the
     *                          full F11.6 overload, not for the S20 one)
     * @param netCapacityPoints {@code netCapacityDays * pointsPerDay}, or {@code null} if no
     *                          {@code pointsPerDay} was configured (leaf) or not every
     *                          aggregated child had one (PI Planning)
     * @param isProvisional     {@code true} for every S20-overload result; caller-determined for
     *                          the full F11.6 overload (US11.6.5 §Architecture)
     */
    public record Summary(
            int durationDays,
            int workingDays,
            int memberCount,
            int totalAbsenceDays,
            double netCapacityDays,
            Double netCapacityPoints,
            boolean isProvisional) {
    }

    /**
     * Counts the Monday-Friday days in {@code [start, end]}, inclusive on both ends. No holiday
     * exclusion — the S20-compatible overload, see this class's Javadoc.
     *
     * @param start range start date, inclusive
     * @param end   range end date, inclusive
     * @return the number of weekday (non Saturday/Sunday) dates in the range
     */
    public static int countWorkingDays(final LocalDate start, final LocalDate end) {
        return countWorkingDays(start, end, Set.of());
    }

    /**
     * Counts the Monday-Friday days in {@code [start, end]}, inclusive on both ends, excluding
     * any date present in {@code holidays} (US11.6.1).
     *
     * @param start    range start date, inclusive
     * @param end      range end date, inclusive
     * @param holidays tenant holiday dates to additionally exclude — empty for S20-equivalent
     *                 (weekends-only) behavior
     * @return the number of working dates in the range
     */
    public static int countWorkingDays(final LocalDate start, final LocalDate end, final Set<LocalDate> holidays) {
        if (end.isBefore(start)) {
            return 0;
        }
        int count = 0;
        for (LocalDate day = start; !day.isAfter(end); day = day.plusDays(1)) {
            DayOfWeek dayOfWeek = day.getDayOfWeek();
            boolean isWeekend = dayOfWeek.equals(DayOfWeek.SATURDAY) || dayOfWeek.equals(DayOfWeek.SUNDAY);
            if (!isWeekend && !holidays.contains(day)) {
                count++;
            }
        }
        return count;
    }

    /**
     * Computes the provisional (weekends-only, no focus factor) net-capacity summary for a leaf
     * ({@code SPRINT}/{@code RELEASE}/{@code CUSTOM}) event, per US11.1.2's original formula —
     * kept for backward compatibility, see this class's Javadoc.
     *
     * @param startDate    the event's start date
     * @param endDate      the event's end date
     * @param members      the event's roster contributions
     * @param pointsPerDay the event's points-per-day conversion factor, or {@code null}
     * @return the computed summary, {@code isProvisional} always {@code true}
     */
    public static Summary summarize(
            final LocalDate startDate,
            final LocalDate endDate,
            final List<MemberInput> members,
            final Double pointsPerDay) {
        return summarizeInternal(startDate, endDate, members, pointsPerDay, Set.of(), 100, true);
    }

    /**
     * Computes the full F11.6-engine net-capacity summary for a leaf event: holiday-aware working
     * days (US11.6.1) and per-member effective focus factor (US11.6.2 — member override, else
     * event override, else {@code eventFocusFactorPercent} as already resolved by the caller from
     * team maturity/global default, US11.6.4).
     *
     * @param startDate               the event's start date
     * @param endDate                 the event's end date
     * @param members                 the event's roster contributions
     * @param pointsPerDay            the event's points-per-day conversion factor, or {@code null}
     * @param holidays                tenant holiday dates to exclude alongside weekends
     * @param eventFocusFactorPercent the event-level effective focus factor, {@code [10, 100]} —
     *                                already resolved by the caller (event override, else team
     *                                maturity default, else global 70%)
     * @param isProvisional           the caller-determined provisional flag (US11.6.5
     *                                §Architecture) — {@code false} once holidays/maturity/focus
     *                                are genuinely configured, {@code true} otherwise
     * @return the computed summary
     */
    public static Summary summarize(
            final LocalDate startDate,
            final LocalDate endDate,
            final List<MemberInput> members,
            final Double pointsPerDay,
            final Set<LocalDate> holidays,
            final int eventFocusFactorPercent,
            final boolean isProvisional) {
        return summarizeInternal(startDate, endDate, members, pointsPerDay, holidays, eventFocusFactorPercent, isProvisional);
    }

    private static Summary summarizeInternal(
            final LocalDate startDate,
            final LocalDate endDate,
            final List<MemberInput> members,
            final Double pointsPerDay,
            final Set<LocalDate> holidays,
            final int eventFocusFactorPercent,
            final boolean isProvisional) {
        int durationDays = (int) ChronoUnit.DAYS.between(startDate, endDate) + 1;
        int workingDays = countWorkingDays(startDate, endDate, holidays);

        List<MemberInput> contributing = members.stream().filter(member -> !member.excluded()).toList();
        int totalAbsenceDays = 0;
        double netCapacityDays = 0;
        for (MemberInput member : contributing) {
            int memberAbsenceDays = 0;
            for (AbsenceRange absence : member.absences()) {
                LocalDate overlapStart = absence.start().isAfter(startDate) ? absence.start() : startDate;
                LocalDate overlapEnd = absence.end().isBefore(endDate) ? absence.end() : endDate;
                memberAbsenceDays += countWorkingDays(overlapStart, overlapEnd, holidays);
            }
            totalAbsenceDays += memberAbsenceDays;
            int effectiveFocus = member.focusFactorPercent() != null ? member.focusFactorPercent() : eventFocusFactorPercent;
            double memberNetDays = Math.max(0, workingDays - memberAbsenceDays)
                    * (member.availabilityPercent() / 100.0)
                    * (effectiveFocus / 100.0);
            netCapacityDays += memberNetDays;
        }

        Double netCapacityPoints = pointsPerDay != null ? netCapacityDays * pointsPerDay : null;
        return new Summary(
                durationDays, workingDays, contributing.size(), totalAbsenceDays, netCapacityDays, netCapacityPoints,
                isProvisional);
    }

    /**
     * Aggregates a PI Planning/Increment event's summary from its children's already-computed
     * summaries (US11.1.2/US11.3.1) — recursion is exactly one level, the max-depth-2 invariant
     * guarantees no further nesting. Callers exclude IP-iteration children (US11.5.1) from {@code
     * childSummaries} before calling this method — this class has no notion of that flag.
     *
     * @param startDate      the PI Planning/Increment event's start date
     * @param endDate        the PI Planning/Increment event's end date
     * @param childSummaries the non-IP-iteration children's own summaries; empty if the PI has no
     *                       (eligible) children yet
     * @return the aggregated summary — {@code netCapacityDays: 0}/{@code netCapacityPoints: null}
     *     for a childless PI (normal transitional state, not an error); {@code isProvisional} is
     *     {@code true} only if every child summary is itself provisional
     */
    public static Summary aggregate(
            final LocalDate startDate, final LocalDate endDate, final List<Summary> childSummaries) {
        int durationDays = (int) ChronoUnit.DAYS.between(startDate, endDate) + 1;
        int workingDays = countWorkingDays(startDate, endDate);
        if (childSummaries.isEmpty()) {
            return new Summary(durationDays, workingDays, 0, 0, 0, null, true);
        }

        int memberCount = childSummaries.stream().mapToInt(Summary::memberCount).sum();
        int totalAbsenceDays = childSummaries.stream().mapToInt(Summary::totalAbsenceDays).sum();
        double netCapacityDays = childSummaries.stream().mapToDouble(Summary::netCapacityDays).sum();
        boolean everyChildHasPoints = childSummaries.stream().allMatch(child -> child.netCapacityPoints() != null);
        Double netCapacityPoints = everyChildHasPoints
                ? childSummaries.stream().mapToDouble(Summary::netCapacityPoints).sum()
                : null;
        boolean allProvisional = childSummaries.stream().allMatch(Summary::isProvisional);
        return new Summary(
                durationDays, workingDays, memberCount, totalAbsenceDays, netCapacityDays, netCapacityPoints,
                allProvisional);
    }
}

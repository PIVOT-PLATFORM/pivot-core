package fr.pivot.agilite.capacity;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * Pure, persistence-free computation of a capacity event's provisional net-capacity summary
 * (US11.1.2).
 *
 * <p><strong>Deliberately simplified, explicitly provisional — NOT the full F11.6 engine</strong>
 * (US11.1.2 §Architecture, Gate 1 decision): working days exclude weekends only (no holidays by
 * locality — {@code US11.6.1}, Sprint 21), no focus factor, no velocity/maturity adjustment.
 * {@code isProvisional} is always {@code true} on every {@link Summary} this class produces —
 * the real F11.6 engine will replace this formula, not add to it. Deliberately decoupled from
 * JPA/Spring so it can be exercised in plain JUnit without a database — same posture as {@code
 * fr.pivot.agilite.pi.PiIterationGenerator}.
 */
public final class CapacityCalculator {

    private CapacityCalculator() {
    }

    /**
     * A single absence period, already clipped to nothing — clipping to the owning event's
     * period happens inside {@link #summarize}.
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
     */
    public record MemberInput(boolean excluded, int availabilityPercent, List<AbsenceRange> absences) {
    }

    /**
     * The computed, always-provisional capacity summary for one event (leaf or aggregated).
     *
     * @param durationDays      total calendar days in the event's period, inclusive
     * @param workingDays       Monday-Friday days in the event's period, inclusive
     * @param memberCount       number of non-excluded members contributing (0 for an empty
     *                          leaf's roster or a childless PI Planning event)
     * @param totalAbsenceDays  sum of each contributing member's working-day absence overlap
     * @param netCapacityDays   the provisional net capacity, in person-days
     * @param netCapacityPoints {@code netCapacityDays * pointsPerDay}, or {@code null} if no
     *                          {@code pointsPerDay} was configured (leaf) or not every
     *                          aggregated child had one (PI Planning)
     * @param isProvisional     always {@code true} — see this class's Javadoc
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
     * exclusion — see this class's Javadoc.
     *
     * @param start range start date, inclusive
     * @param end   range end date, inclusive
     * @return the number of weekday (non Saturday/Sunday) dates in the range
     */
    public static int countWorkingDays(final LocalDate start, final LocalDate end) {
        if (end.isBefore(start)) {
            return 0;
        }
        int count = 0;
        for (LocalDate day = start; !day.isAfter(end); day = day.plusDays(1)) {
            DayOfWeek dayOfWeek = day.getDayOfWeek();
            if (!dayOfWeek.equals(DayOfWeek.SATURDAY) && !dayOfWeek.equals(DayOfWeek.SUNDAY)) {
                count++;
            }
        }
        return count;
    }

    /**
     * Computes the provisional net-capacity summary for a leaf ({@code SPRINT}/{@code RELEASE}/
     * {@code CUSTOM}) event, per US11.1.2's exact formula.
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
        int durationDays = (int) ChronoUnit.DAYS.between(startDate, endDate) + 1;
        int workingDays = countWorkingDays(startDate, endDate);

        List<MemberInput> contributing = members.stream().filter(member -> !member.excluded()).toList();
        int totalAbsenceDays = 0;
        double netCapacityDays = 0;
        for (MemberInput member : contributing) {
            int memberAbsenceDays = 0;
            for (AbsenceRange absence : member.absences()) {
                LocalDate overlapStart = absence.start().isAfter(startDate) ? absence.start() : startDate;
                LocalDate overlapEnd = absence.end().isBefore(endDate) ? absence.end() : endDate;
                memberAbsenceDays += countWorkingDays(overlapStart, overlapEnd);
            }
            totalAbsenceDays += memberAbsenceDays;
            double memberNetDays = Math.max(0, workingDays - memberAbsenceDays) * (member.availabilityPercent() / 100.0);
            netCapacityDays += memberNetDays;
        }

        Double netCapacityPoints = pointsPerDay != null ? netCapacityDays * pointsPerDay : null;
        return new Summary(
                durationDays, workingDays, contributing.size(), totalAbsenceDays, netCapacityDays, netCapacityPoints, true);
    }

    /**
     * Aggregates a PI Planning event's summary from its children's already-computed summaries
     * (US11.1.2/US11.3.1) — recursion is exactly one level, the max-depth-2 invariant guarantees
     * no further nesting.
     *
     * @param startDate      the PI Planning event's start date
     * @param endDate        the PI Planning event's end date
     * @param childSummaries the children's own summaries; empty if the PI has no children yet
     * @return the aggregated summary — {@code netCapacityDays: 0}/{@code netCapacityPoints: null}
     *     for a childless PI (normal transitional state, not an error)
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
        return new Summary(
                durationDays, workingDays, memberCount, totalAbsenceDays, netCapacityDays, netCapacityPoints, true);
    }
}

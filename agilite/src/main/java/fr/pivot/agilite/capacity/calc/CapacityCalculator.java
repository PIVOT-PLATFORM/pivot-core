package fr.pivot.agilite.capacity.calc;

import fr.pivot.agilite.capacity.CapacityMaturityLevel;
import fr.pivot.agilite.capacity.dto.CapacityAbsenceInput;
import fr.pivot.agilite.capacity.dto.CapacityEventInput;
import fr.pivot.agilite.capacity.dto.CapacityMemberInput;
import fr.pivot.agilite.capacity.dto.EventCapacityResult;
import fr.pivot.agilite.capacity.dto.MaturityProfile;
import fr.pivot.agilite.capacity.dto.MemberCapacityResult;
import fr.pivot.agilite.capacity.dto.PiCapacityResult;
import fr.pivot.agilite.capacity.dto.SprintContribution;
import fr.pivot.agilite.capacity.dto.VelocityForecast;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

/**
 * Pure capacity-planning calculation engine (E11 — capacity planning) — no persistence, no I/O,
 * no Spring dependency, independently unit-testable. Ported from the PouetPouet POC's {@code
 * apps/web/src/lib/capacity.ts}, extended with the features called out in the E11 design (server-
 * authoritative injected holidays, maturity-level defaults, recommended engagement margin, and a
 * rolling-window velocity/coefficient-of-variation forecast).
 *
 * <h2>Model</h2>
 *
 * <ul>
 *   <li>A "working day" is a calendar day whose weekday is in {@code workingDays} ({@code 0} =
 *       Sunday .. {@code 6} = Saturday, see {@link #weekdayIndex}) and that is not one of the
 *       injected {@code holidays}.
 *   <li>{@code joursHommeNets(m) = joursOuvres × quotite} — net person-days <strong>without</strong>
 *       focus applied. Feeds {@code points(m)}; never focus-adjusted a second time.
 *   <li>{@code capaciteNette(m) = joursHommeNets(m) × focus} — net capacity <strong>with</strong>
 *       focus applied exactly once. Feeds the sprint/PI person-day aggregates and {@code
 *       engagementRecommande}.
 *   <li>{@code engagementRecommande(m) = capaciteNette(m) × (1 − marge)}.
 *   <li>{@code points(m) = joursHommeNets(m) × pointsParJour} — deliberately built from the
 *       focus-<em>free</em> person-days, same rationale as the POC's own {@code
 *       pointsPerPersonDay} ("the empirical net velocity — focus already baked in").
 * </ul>
 *
 * <h2>Maturity defaults</h2>
 *
 * A {@link CapacityMaturityLevel} (or the unset/{@code null} case) provides the default focus
 * factor and safety margin used when an event does not override them, and a {@code
 * velocityMultiplier} consumed by {@link #maturityAdjustedCapacity}:
 *
 * <table>
 *   <caption>Default maturity profile</caption>
 *   <tr><th>Level</th><th>focus</th><th>margin</th><th>velocity multiplier</th></tr>
 *   <tr><td>{@link CapacityMaturityLevel#FORMING}</td><td>0.60</td><td>0.20</td><td>0.80</td></tr>
 *   <tr><td>{@link CapacityMaturityLevel#NORMING}</td><td>0.70</td><td>0.10</td><td>0.90</td></tr>
 *   <tr><td>{@link CapacityMaturityLevel#PERFORMING}</td><td>0.80</td><td>0.05</td><td>0.95</td></tr>
 *   <tr><td>unset ({@code null})</td><td>0.70</td><td>0.15</td><td>0.85</td></tr>
 * </table>
 *
 * <h2>Focus resolution</h2>
 *
 * Precedence, highest first: {@link CapacityMemberInput#focusFactor()} &gt; {@link
 * CapacityEventInput#roleFocusFactors()} (keyed by {@link CapacityMemberInput#role()}) &gt; {@link
 * CapacityEventInput#focusFactor()} &gt; {@link #maturityProfile}'s default. Every candidate
 * actually used is validated to be within {@code [0, 1]} — see {@link #resolveFocusFactor}.
 */
public final class CapacityCalculator {

    /** {@link CapacityMaturityLevel#FORMING} default profile. */
    public static final MaturityProfile MATURITY_FORMING = new MaturityProfile(0.60, 0.20, 0.80);

    /** {@link CapacityMaturityLevel#NORMING} default profile. */
    public static final MaturityProfile MATURITY_NORMING = new MaturityProfile(0.70, 0.10, 0.90);

    /** {@link CapacityMaturityLevel#PERFORMING} default profile. */
    public static final MaturityProfile MATURITY_PERFORMING = new MaturityProfile(0.80, 0.05, 0.95);

    /** Default profile used when {@link CapacityEventInput#maturityLevel()} is {@code null}. */
    public static final MaturityProfile MATURITY_DEFAULT = new MaturityProfile(0.70, 0.15, 0.85);

    /** Coefficient-of-variation threshold above which {@link #forecastVelocity}'s interval widens. */
    private static final double CV_WIDEN_THRESHOLD = 0.25;

    /**
     * Sigma multiplier applied to the forecast interval when the coefficient of variation exceeds
     * {@link #CV_WIDEN_THRESHOLD} (high variability — a wider interval better reflects the
     * uncertainty).
     */
    private static final double SIGMA_MULTIPLIER_WIDENED = 2.0;

    /**
     * Sigma multiplier applied to the forecast interval when the coefficient of variation is at or
     * below {@link #CV_WIDEN_THRESHOLD} (low variability — a tighter interval is warranted).
     */
    private static final double SIGMA_MULTIPLIER_TIGHTENED = 0.5;

    private static final int ROUNDING_FACTOR = 100;

    private CapacityCalculator() {
    }

    // ── Maturity ────────────────────────────────────────────────────────────────

    /**
     * Returns the default profile for a maturity level, or {@link #MATURITY_DEFAULT} for the
     * unset ({@code null}) case.
     *
     * @param level the team maturity level, or {@code null}
     * @return the corresponding default profile
     */
    public static MaturityProfile maturityProfile(final CapacityMaturityLevel level) {
        if (level == null) {
            return MATURITY_DEFAULT;
        }
        return switch (level) {
            case FORMING -> MATURITY_FORMING;
            case NORMING -> MATURITY_NORMING;
            case PERFORMING -> MATURITY_PERFORMING;
        };
    }

    /**
     * Applies the maturity level's {@code velocityMultiplier} to a sprint's raw {@code
     * totalCapaciteNette} — a less mature team's raw person-day capacity is tempered down towards
     * its likely real output.
     *
     * @param totalCapaciteNette the sprint's raw {@link EventCapacityResult#totalCapaciteNette()}
     * @param level              the team maturity level, or {@code null} for the default profile
     * @return {@code totalCapaciteNette × maturityProfile(level).velocityMultiplier()}, rounded to
     *     2 decimals
     */
    public static double maturityAdjustedCapacity(final double totalCapaciteNette, final CapacityMaturityLevel level) {
        return round2(totalCapaciteNette * maturityProfile(level).velocityMultiplier());
    }

    // ── Dates ───────────────────────────────────────────────────────────────────

    /**
     * Maps a {@link LocalDate} to the POC's weekday convention: {@code 0} = Sunday .. {@code 6} =
     * Saturday (unlike {@link DayOfWeek#getValue()}, which is {@code 1} = Monday .. {@code 7} =
     * Sunday).
     *
     * @param date the date to map
     * @return the weekday index, {@code 0} (Sunday) .. {@code 6} (Saturday)
     */
    public static int weekdayIndex(final LocalDate date) {
        final int isoValue = date.getDayOfWeek().getValue(); // 1 (Mon) .. 7 (Sun)
        return isoValue % 7; // Sun(7)->0, Mon(1)->1, ..., Sat(6)->6
    }

    /**
     * Counts the calendar days (inclusive) in {@code [start, end]} whose weekday is in {@code
     * workingDays} and that are not listed in {@code holidays}.
     *
     * @param start       the period's first day (inclusive)
     * @param end         the period's last day (inclusive) — {@code 0} is returned if before
     *                    {@code start}
     * @param workingDays weekdays counted as working days, {@code 0} (Sunday) .. {@code 6}
     *                    (Saturday)
     * @param holidays    calendar days excluded regardless of weekday, never {@code null} (pass
     *                    {@link Set#of()} for none)
     * @return the number of matching working days
     */
    public static int countWorkingDays(
            final LocalDate start, final LocalDate end, final Set<Integer> workingDays, final Set<LocalDate> holidays) {
        if (end.isBefore(start)) {
            return 0;
        }
        int count = 0;
        for (LocalDate cursor = start; !cursor.isAfter(end); cursor = cursor.plusDays(1)) {
            if (workingDays.contains(weekdayIndex(cursor)) && !holidays.contains(cursor)) {
                count++;
            }
        }
        return count;
    }

    /**
     * Counts the working days of an absence that fall inside the given period (before the
     * absence's {@code fraction} is applied) — the absence is clipped to the period first.
     *
     * @param absence     the absence, whose dates may extend beyond the period on either side
     * @param periodStart the period's first day (inclusive)
     * @param periodEnd   the period's last day (inclusive)
     * @param workingDays weekdays counted as working days
     * @param holidays    calendar days excluded regardless of weekday
     * @return the clipped working-day count, {@code 0} if the absence does not overlap the period
     */
    public static int absenceWorkingDays(
            final CapacityAbsenceInput absence,
            final LocalDate periodStart,
            final LocalDate periodEnd,
            final Set<Integer> workingDays,
            final Set<LocalDate> holidays) {
        final LocalDate clippedStart = absence.startDate().isBefore(periodStart) ? periodStart : absence.startDate();
        final LocalDate clippedEnd = absence.endDate().isAfter(periodEnd) ? periodEnd : absence.endDate();
        return countWorkingDays(clippedStart, clippedEnd, workingDays, holidays);
    }

    // ── Focus resolution ────────────────────────────────────────────────────────

    /**
     * Resolves the focus factor to apply for a member, following the member &gt; role &gt; event
     * &gt; maturity-default precedence, validating whichever candidate is actually used.
     *
     * @param member the member (its own {@link CapacityMemberInput#focusFactor()}/{@link
     *               CapacityMemberInput#role()} are consulted)
     * @param event  the owning event (its {@link CapacityEventInput#roleFocusFactors()}/{@link
     *               CapacityEventInput#focusFactor()}/{@link CapacityEventInput#maturityLevel()}
     *               are consulted, in that order, as fallbacks)
     * @return the resolved focus factor, always within {@code [0, 1]}
     * @throws IllegalArgumentException if the resolved candidate is outside {@code [0, 1]}
     */
    public static double resolveFocusFactor(final CapacityMemberInput member, final CapacityEventInput event) {
        if (member.focusFactor() != null) {
            return validateFocus(member.focusFactor());
        }
        if (member.role() != null && event.roleFocusFactors() != null) {
            final Double roleFocus = event.roleFocusFactors().get(member.role());
            if (roleFocus != null) {
                return validateFocus(roleFocus);
            }
        }
        if (event.focusFactor() != null) {
            return validateFocus(event.focusFactor());
        }
        return maturityProfile(event.maturityLevel()).focusFactor();
    }

    private static double validateFocus(final double focus) {
        if (focus < 0 || focus > 1) {
            throw new IllegalArgumentException("Focus factor must be within [0, 1], got: " + focus);
        }
        return focus;
    }

    // ── Per-member / per-event capacity ────────────────────────────────────────

    /**
     * Computes one member's capacity over the event's period.
     *
     * @param member the member to compute
     * @param event  the owning event
     * @return the member's computed capacity
     */
    public static MemberCapacityResult computeMemberCapacity(final CapacityMemberInput member, final CapacityEventInput event) {
        final int totalWorkingDays =
                countWorkingDays(event.startDate(), event.endDate(), event.workingDays(), event.holidays());

        double absentDays = 0;
        for (CapacityAbsenceInput absence : member.absences()) {
            absentDays += absenceWorkingDays(absence, event.startDate(), event.endDate(), event.workingDays(), event.holidays())
                    * absence.fraction();
        }
        final double availableDays = Math.max(0, totalWorkingDays - absentDays);

        final double joursHommeNets = round2(availableDays * member.quotite());
        final double effectiveFocus = resolveFocusFactor(member, event);
        final double capaciteNette = round2(joursHommeNets * effectiveFocus);

        final double margin = event.margeSecurite() != null
                ? event.margeSecurite()
                : maturityProfile(event.maturityLevel()).margin();
        final double engagementRecommande = round2(capaciteNette * (1 - margin));

        final Double points = event.pointsPerDay() != null ? round2(joursHommeNets * event.pointsPerDay()) : null;

        return new MemberCapacityResult(
                member.id(), effectiveFocus, round2(absentDays), joursHommeNets, capaciteNette, points, engagementRecommande);
    }

    /**
     * Computes the full event's capacity: every member individually (see {@link
     * #computeMemberCapacity}), plus totals aggregated over the non-{@link
     * CapacityMemberInput#excluded()} members.
     *
     * @param event the event to compute
     * @return the event's computed capacity
     */
    public static EventCapacityResult computeEventCapacity(final CapacityEventInput event) {
        final int totalWorkingDays =
                countWorkingDays(event.startDate(), event.endDate(), event.workingDays(), event.holidays());

        final List<CapacityMemberInput> sortedMembers = new ArrayList<>(event.members());
        sortedMembers.sort(Comparator.comparingInt(CapacityMemberInput::position));

        final List<MemberCapacityResult> results = new ArrayList<>(sortedMembers.size());
        double totalJoursHommeNets = 0;
        double totalCapaciteNette = 0;
        double totalPoints = 0;
        boolean anyPoints = event.pointsPerDay() != null;
        double totalEngagementRecommande = 0;

        for (CapacityMemberInput member : sortedMembers) {
            final MemberCapacityResult result = computeMemberCapacity(member, event);
            results.add(result);
            if (!member.excluded()) {
                totalJoursHommeNets += result.joursHommeNets();
                totalCapaciteNette += result.capaciteNette();
                totalEngagementRecommande += result.engagementRecommande();
                if (anyPoints && result.points() != null) {
                    totalPoints += result.points();
                }
            }
        }

        final Double roundedTotalPoints = anyPoints ? round2(totalPoints) : null;

        final Double loadRatio = roundedTotalPoints != null && event.committedPoints() != null && roundedTotalPoints > 0
                ? round2(event.committedPoints() / roundedTotalPoints)
                : null;
        final Double predictability = event.committedPoints() != null && event.completedPoints() != null
                && event.committedPoints() > 0
                ? round2(event.completedPoints() / event.committedPoints())
                : null;

        return new EventCapacityResult(
                totalWorkingDays,
                results,
                round2(totalJoursHommeNets),
                round2(totalCapaciteNette),
                roundedTotalPoints,
                round2(totalEngagementRecommande),
                loadRatio,
                predictability);
    }

    // ── PI consolidation ────────────────────────────────────────────────────────

    /**
     * Throws if adding one more nesting level would exceed E11's depth-2 limit (a PI's sprints, no
     * deeper) — call with the candidate parent's own parent-presence before attaching a new child.
     *
     * @param parentAlreadyHasParent {@code true} if the intended parent event itself already has a
     *                                non-{@code null} {@code parentId}
     * @throws IllegalArgumentException if {@code parentAlreadyHasParent} is {@code true}
     */
    public static void requireMaxDepth(final boolean parentAlreadyHasParent) {
        if (parentAlreadyHasParent) {
            throw new IllegalArgumentException(
                    "Capacity event nesting cannot exceed 2 levels (a PI's sprints may not themselves have children)");
        }
    }

    /**
     * Consolidates a PI's capacity from its direct sprint children (depth-2 by construction — see
     * {@link #requireMaxDepth}, enforced by the caller when building the hierarchy, not by this
     * pure aggregation).
     *
     * @param sprints                the PI's direct sprint children, each with its own {@link
     *                                SprintContribution#ipSprint()} flag
     * @param applySafeIpExclusion   {@code true} to exclude {@link SprintContribution#ipSprint()}
     *                                sprints from the totals (SAFe framework in use) — {@code
     *                                false} to include every sprint regardless of the flag
     * @return the PI's consolidated capacity
     */
    public static PiCapacityResult consolidatePi(final List<SprintContribution> sprints, final boolean applySafeIpExclusion) {
        double totalJoursHommeNets = 0;
        double totalCapaciteNette = 0;
        double totalPoints = 0;
        boolean anyPoints = false;
        int included = 0;
        int excludedIp = 0;

        for (SprintContribution sprint : sprints) {
            if (applySafeIpExclusion && sprint.ipSprint()) {
                excludedIp++;
                continue;
            }
            included++;
            final EventCapacityResult capacity = sprint.sprintCapacity();
            totalJoursHommeNets += capacity.totalJoursHommeNets();
            totalCapaciteNette += capacity.totalCapaciteNette();
            if (capacity.totalPoints() != null) {
                anyPoints = true;
                totalPoints += capacity.totalPoints();
            }
        }

        return new PiCapacityResult(
                round2(totalJoursHommeNets),
                round2(totalCapaciteNette),
                anyPoints ? round2(totalPoints) : null,
                included,
                excludedIp);
    }

    // ── Velocity forecast ───────────────────────────────────────────────────────

    /**
     * Forecasts the next sprint's velocity from a rolling window of the team's most recent
     * sprints, with a coefficient-of-variation-driven confidence interval.
     *
     * @param pointsLivresHistory each past sprint's completed points, oldest first, most recent
     *                            last; a {@code null} entry marks an "empty" sprint (no data),
     *                            excluded from the sample
     * @param window              how many of the most recent entries to consider before excluding
     *                            empty ones, must be {@code > 0} (E11 default: {@code 3})
     * @return the forecast, or {@code null} if the window contains no non-empty sprint (use {@link
     *     #firstSprintFallback} instead in that case)
     * @throws IllegalArgumentException if {@code window <= 0}
     */
    public static VelocityForecast forecastVelocity(final List<Double> pointsLivresHistory, final int window) {
        if (window <= 0) {
            throw new IllegalArgumentException("window must be > 0, got: " + window);
        }
        final int fromIndex = Math.max(0, pointsLivresHistory.size() - window);
        final List<Double> sample = new ArrayList<>();
        for (Double value : pointsLivresHistory.subList(fromIndex, pointsLivresHistory.size())) {
            if (value != null) {
                sample.add(value);
            }
        }
        if (sample.isEmpty()) {
            return null;
        }

        final int sampleSize = sample.size();
        double sum = 0;
        for (double value : sample) {
            sum += value;
        }
        final double mean = sum / sampleSize;

        double squaredDiffSum = 0;
        for (double value : sample) {
            squaredDiffSum += (value - mean) * (value - mean);
        }
        final double stdDev = Math.sqrt(squaredDiffSum / sampleSize);

        final double coefficientOfVariation = mean != 0 ? stdDev / mean : 0;
        final boolean widened = coefficientOfVariation > CV_WIDEN_THRESHOLD;
        final double sigmaMultiplier = widened ? SIGMA_MULTIPLIER_WIDENED : SIGMA_MULTIPLIER_TIGHTENED;

        return new VelocityForecast(
                sampleSize,
                round2(mean),
                round2(stdDev),
                round2(coefficientOfVariation),
                round2(mean - sigmaMultiplier * stdDev),
                round2(mean + sigmaMultiplier * stdDev),
                widened);
    }

    /**
     * Fallback forecast for a sprint with no velocity history at all (first sprint of a team, or
     * every entry in the window is empty) — equal to the sum of {@link
     * MemberCapacityResult#engagementRecommande()} across the sprint's non-excluded members, since
     * {@code capaciteNette} already includes focus: {@code joursHomme × focus × (1 − marge) =
     * capaciteNette × (1 − marge)}.
     *
     * @param totalCapaciteNette the sprint's {@link EventCapacityResult#totalCapaciteNette()}
     * @param margin             the margin actually applied (the event's own {@code
     *                           margeSecurite}, or {@link #maturityProfile}'s default)
     * @return the fallback forecast points, rounded to 2 decimals
     */
    public static double firstSprintFallback(final double totalCapaciteNette, final double margin) {
        return round2(totalCapaciteNette * (1 - margin));
    }

    // ── Misc ────────────────────────────────────────────────────────────────────

    /**
     * Rounds a value to 2 decimal places, matching the PouetPouet POC's own {@code round2}.
     *
     * @param value the raw value
     * @return the rounded value
     */
    public static double round2(final double value) {
        return Math.round(value * ROUNDING_FACTOR) / (double) ROUNDING_FACTOR;
    }
}

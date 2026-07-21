package fr.pivot.agilite.capacity.cadence;

import fr.pivot.agilite.capacity.exception.CapacityValidationException;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

/**
 * Pure date-layout engine for F11.5 PI/SAFe cadence: given a PI's window and a {@link
 * CadenceRequest}, computes the child {@code SPRINT} slots that would tile it — no I/O, no
 * persistence, easy to unit-test exhaustively. {@code CapacityEventService} is the only caller,
 * turning each returned {@link SprintPlan} into a persisted {@code CapacityEvent}.
 *
 * <p><strong>Period auto|manual.</strong> This class is the "auto" side: sprint dates are
 * computed by tiling the PI window with fixed-length slots. The "manuel" side is simply not using
 * this planner — the caller creates each {@code SPRINT} event individually via the existing
 * events CRUD (F11.1), picking its own dates.
 */
public final class CadencePlanner {

    private CadencePlanner() {
    }

    /**
     * Lays out a PI's sprints according to a cadence spec.
     *
     * @param piStartDate the PI's first calendar day (inclusive)
     * @param piEndDate   the PI's last calendar day (inclusive), never before {@code piStartDate}
     * @param request     the cadence spec
     * @return the computed sprint slots, in chronological order, the trailing entry marked {@code
     *         ipSprint} when {@code request.includeIpSprint()} is {@code true}
     * @throws CapacityValidationException with code {@code INVALID_CADENCE} if neither or both of
     *                                      {@code sprintLengthDays}/{@code sprintLengthWeeks} are
     *                                      supplied, or the supplied length is not positive; with
     *                                      code {@code CADENCE_OVERFLOW} if the requested sprints
     *                                      (plus the IP sprint, when requested) do not fit within
     *                                      the PI's window
     */
    public static List<SprintPlan> plan(
            final LocalDate piStartDate, final LocalDate piEndDate, final CadenceRequest request) {
        final int sprintLengthDays = resolveSprintLengthDays(request);
        final int sprintCount = request.sprintCount();
        final boolean includeIpSprint = request.includeIpSprint();
        final String namePrefix = resolveNamePrefix(request.namePrefix());

        final long piWindowDays = ChronoUnit.DAYS.between(piStartDate, piEndDate) + 1;
        final long totalPlannedDays = (long) sprintLengthDays * sprintCount
                + (includeIpSprint ? sprintLengthDays : 0);
        if (totalPlannedDays > piWindowDays) {
            throw new CapacityValidationException(
                    "CADENCE_OVERFLOW",
                    "Requested cadence (" + totalPlannedDays + " days) exceeds the PI window ("
                            + piWindowDays + " days)");
        }

        final List<SprintPlan> plans = new ArrayList<>();
        LocalDate cursor = piStartDate;
        for (int index = 1; index <= sprintCount; index++) {
            final LocalDate sprintEnd = cursor.plusDays(sprintLengthDays - 1L);
            plans.add(new SprintPlan(namePrefix + " " + index, cursor, sprintEnd, false));
            cursor = sprintEnd.plusDays(1);
        }
        if (includeIpSprint) {
            final LocalDate ipEnd = cursor.plusDays(sprintLengthDays - 1L);
            plans.add(new SprintPlan(namePrefix + " " + (sprintCount + 1) + " (IP)", cursor, ipEnd, true));
        }
        return plans;
    }

    /**
     * Resolves the single sprint length, in days, from the request's mutually-exclusive {@code
     * sprintLengthDays}/{@code sprintLengthWeeks} fields.
     *
     * @param request the cadence spec
     * @return the resolved sprint length in days, always positive
     * @throws CapacityValidationException with code {@code INVALID_CADENCE} if neither or both
     *                                      fields are supplied, or the supplied value is not
     *                                      positive
     */
    private static int resolveSprintLengthDays(final CadenceRequest request) {
        final Integer days = request.sprintLengthDays();
        final Integer weeks = request.sprintLengthWeeks();
        if ((days == null) == (weeks == null)) {
            throw new CapacityValidationException(
                    "INVALID_CADENCE", "Exactly one of sprintLengthDays/sprintLengthWeeks is required");
        }
        final int resolved = days != null ? days : weeks * 7;
        if (resolved <= 0) {
            throw new CapacityValidationException(
                    "INVALID_CADENCE", "Sprint length must be a positive number of days");
        }
        return resolved;
    }

    /**
     * Resolves the display-name prefix, defaulting to {@code "Sprint"} when blank or absent.
     *
     * @param namePrefix the caller-supplied prefix, may be {@code null} or blank
     * @return the resolved, non-blank prefix
     */
    private static String resolveNamePrefix(final String namePrefix) {
        return namePrefix == null || namePrefix.isBlank() ? "Sprint" : namePrefix;
    }
}

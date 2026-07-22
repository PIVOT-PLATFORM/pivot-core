package fr.pivot.agilite.pi;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * Pure, persistence-free generation of a {@link PiCycle}'s iterations at creation time
 * (US50.1.1).
 *
 * <p>Deliberately decoupled from JPA/Spring so it can be exercised in plain JUnit without a
 * database — same posture as {@code fr.pivot.agilite.wheel.WeightedEntrySelector}. Mirrors the
 * {@code generateIterations} function of the PouetPouet reference POC (a straightforward
 * arithmetic walk, no external state): {@code count} consecutive iterations of {@code weeks}
 * weeks each, labeled {@code "IT1"}.. {@code "ITn"}, followed by one final iteration of the same
 * length labeled {@code "IP Sprint"} (Innovation &amp; Planning) — the cadence decoupling
 * decision (Gate 1, US50.1.1) means this never reads Capacity Planning data.
 */
public final class PiIterationGenerator {

    private PiIterationGenerator() {
    }

    /**
     * A single generated iteration, prior to being attached to a persisted {@link PiCycle}.
     *
     * @param number    1-based position, the IP Sprint included as the final number
     * @param label     display label, {@code "IT1"}.. {@code "ITn"} or {@code "IP Sprint"}
     * @param startDate the iteration's start date
     * @param endDate   the iteration's end date (inclusive, {@code startDate + weeks*7 - 1} days)
     */
    public record GeneratedIteration(int number, String label, LocalDate startDate, LocalDate endDate) {
    }

    /**
     * Generates {@code count} regular iterations followed by one final IP Sprint iteration, all
     * consecutive and non-overlapping, all {@code weeks} weeks long.
     *
     * @param startDate the first iteration's start date
     * @param count     number of regular iterations, expected already validated in [1, 12]
     * @param weeks     length of every iteration in weeks, expected already validated in [1, 6]
     * @return the generated iterations, in order ({@code IT1}.. {@code ITn}, {@code IP Sprint})
     */
    public static List<GeneratedIteration> generate(final LocalDate startDate, final int count, final int weeks) {
        List<GeneratedIteration> iterations = new ArrayList<>(count + 1);
        LocalDate cursor = startDate;
        for (int i = 1; i <= count; i++) {
            LocalDate end = cursor.plusWeeks(weeks).minusDays(1);
            iterations.add(new GeneratedIteration(i, "IT" + i, cursor, end));
            cursor = cursor.plusWeeks(weeks);
        }
        LocalDate ipEnd = cursor.plusWeeks(weeks).minusDays(1);
        iterations.add(new GeneratedIteration(count + 1, "IP Sprint", cursor, ipEnd));
        return iterations;
    }
}

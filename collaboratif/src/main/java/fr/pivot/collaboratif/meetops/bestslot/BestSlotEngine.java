package fr.pivot.collaboratif.meetops.bestslot;

import fr.pivot.collaboratif.meetops.availability.AvailabilityPort;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Ranks candidate meeting slots within a booking window (US12.4.1 "Meilleur créneau (classement)")
 * against (a) participant availability, (b) working hours/weekends/holidays of the locality, and
 * (c) the requested duration plus a trailing buffer.
 *
 * <p><strong>Determinism (US12.4.1 AC).</strong> Candidates are generated in chronological order
 * and sorted with {@link List#sort}, whose {@code TimSort}-backed implementation is guaranteed
 * stable in the JDK — combined with an explicit secondary {@code slotStart} ascending comparator
 * key, two candidates with strictly equal availability always come out in the same, reproducible
 * order (start-ascending) run after run. No {@code HashMap}/{@code HashSet} iteration is ever
 * used to build the candidate sequence, precisely to avoid a non-deterministic source creeping
 * in.
 *
 * <p><strong>"Moins mauvais créneau" (US12.4.1 AC "Error — aucun créneau sans conflit").</strong>
 * The primary pass only considers candidates that fit within working hours (see {@link
 * WorkingHoursCalendar}); each candidate's {@code hasConflict} flag already honestly reflects a
 * partial-availability slot even within working hours, so a window where every participant has
 * some conflict everywhere still yields ranked, flagged candidates rather than an empty result.
 * The rarer case — the window contains not even one working-hours-fitting start time at all (e.g.
 * a window entirely on a single weekend day, or shorter than the requested duration on every
 * working day) — falls back to a second pass that ignores the working-hours constraint, so the
 * engine still proposes something rather than returning nothing; those candidates are always
 * flagged conflicted with an explicit reason.
 */
@Component
public class BestSlotEngine {

    /** Granularity, in minutes, at which candidate slot starts are generated. */
    static final int GRANULARITY_MINUTES = 30;

    /**
     * Trailing buffer, in minutes, required after a candidate slot before the next commitment.
     * <strong>Known, documented default</strong> (like {@link WorkingHoursCalendar}'s locality
     * gap): the AC only asks for "durée demandée + tampon" without pinning an exact duration —
     * 15 minutes is this engine's deliberate choice, not a value derived from the AC text. Making
     * this per-tenant configurable is a follow-up, not attempted here.
     */
    static final int BUFFER_MINUTES = 15;

    /**
     * Maximum number of ranked candidates returned/persisted.
     * <strong>Known, documented default</strong>: the AC only asks for "N créneaux classés"
     * without pinning an exact N — 5 is this engine's deliberate choice, not a value derived from
     * the AC text. Making this per-tenant configurable is a follow-up, not attempted here.
     */
    static final int MAX_CANDIDATES = 5;

    private static final String OUT_OF_WORKING_HOURS_REASON =
            "Aucun créneau disponible dans les heures ouvrées de la période — hors plage ouvrée";

    private final AvailabilityPort availabilityPort;
    private final WorkingHoursCalendar workingHoursCalendar;

    /**
     * Creates the engine with its required dependencies.
     *
     * @param availabilityPort     source of aggregated participant free/busy status
     * @param workingHoursCalendar working-hours/weekend/holiday resolver
     */
    public BestSlotEngine(final AvailabilityPort availabilityPort, final WorkingHoursCalendar workingHoursCalendar) {
        this.availabilityPort = availabilityPort;
        this.workingHoursCalendar = workingHoursCalendar;
    }

    /**
     * Ranks up to {@value #MAX_CANDIDATES} candidate slots for a booking window.
     *
     * @param windowStart          start of the candidate period (inclusive)
     * @param windowEnd            end of the candidate period (exclusive of any candidate's end)
     * @param durationMinutes      requested meeting duration in minutes, strictly positive
     * @param participantRefs      participant identifiers considered for availability
     * @return the ranked candidates, best first, rank-index implicit in list order (index 0 =
     *     rank 1); empty if the window is too short to fit even one candidate of the requested
     *     duration
     */
    public List<SlotCandidate> rank(
            final Instant windowStart, final Instant windowEnd, final int durationMinutes,
            final List<String> participantRefs) {
        List<Instant> candidateStarts = generateStarts(windowStart, windowEnd, durationMinutes);

        List<SlotCandidate> primary = new ArrayList<>();
        for (Instant start : candidateStarts) {
            Instant end = start.plusSeconds(durationMinutes * 60L);
            if (workingHoursCalendar.fitsWorkingHours(start, end, BUFFER_MINUTES)) {
                primary.add(evaluate(start, end, participantRefs, false));
            }
        }

        List<SlotCandidate> candidates = primary;
        if (candidates.isEmpty() && !candidateStarts.isEmpty()) {
            // Fallback pass — see class Javadoc's "moins mauvais créneau" section.
            candidates = new ArrayList<>();
            for (Instant start : candidateStarts) {
                Instant end = start.plusSeconds(durationMinutes * 60L);
                candidates.add(evaluate(start, end, participantRefs, true));
            }
        }

        candidates.sort(
                Comparator.comparingInt(SlotCandidate::availableCount).reversed()
                        .thenComparing(SlotCandidate::start));

        return candidates.size() > MAX_CANDIDATES ? candidates.subList(0, MAX_CANDIDATES) : candidates;
    }

    private List<Instant> generateStarts(final Instant windowStart, final Instant windowEnd, final int durationMinutes) {
        List<Instant> starts = new ArrayList<>();
        Instant latestStart = windowEnd.minusSeconds(durationMinutes * 60L);
        Instant candidate = windowStart;
        while (!candidate.isAfter(latestStart)) {
            starts.add(candidate);
            candidate = candidate.plusSeconds(GRANULARITY_MINUTES * 60L);
        }
        return starts;
    }

    private SlotCandidate evaluate(
            final Instant start, final Instant end, final List<String> participantRefs,
            final boolean outOfWorkingHours) {
        int availableCount = 0;
        for (String participantRef : participantRefs) {
            if (availabilityPort.isAvailable(participantRef, start, end)) {
                availableCount++;
            }
        }
        int totalCount = participantRefs.size();
        boolean availabilityConflict = availableCount < totalCount;
        boolean hasConflict = availabilityConflict || outOfWorkingHours;
        String reason = buildConflictReason(outOfWorkingHours, availabilityConflict, availableCount, totalCount);
        return new SlotCandidate(start, end, availableCount, totalCount, hasConflict, reason);
    }

    private String buildConflictReason(
            final boolean outOfWorkingHours, final boolean availabilityConflict,
            final int availableCount, final int totalCount) {
        if (!outOfWorkingHours && !availabilityConflict) {
            return null;
        }
        String availabilityPart = availabilityConflict
                ? "%d/%d participants indisponibles".formatted(totalCount - availableCount, totalCount)
                : null;
        if (outOfWorkingHours && availabilityPart != null) {
            return OUT_OF_WORKING_HOURS_REASON + " ; " + availabilityPart;
        }
        return outOfWorkingHours ? OUT_OF_WORKING_HOURS_REASON : availabilityPart;
    }
}

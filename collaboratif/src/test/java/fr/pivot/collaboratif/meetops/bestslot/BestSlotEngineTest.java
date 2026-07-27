package fr.pivot.collaboratif.meetops.bestslot;

import fr.pivot.collaboratif.meetops.availability.AvailabilityPort;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link BestSlotEngine} (US12.4.1) — pure logic, no Spring context, exercised
 * against a hand-rolled {@link AvailabilityPort} test double.
 */
class BestSlotEngineTest {

    /** Monday 2026-08-03, 09:00 Europe/Paris — a plain working day. */
    private static final Instant MONDAY_9AM = ZonedDateTime.of(
            2026, 8, 3, 9, 0, 0, 0, java.time.ZoneId.of("Europe/Paris")).toInstant();

    /** Saturday 2026-08-08 — an entire weekend day, no working hours at all. */
    private static final Instant SATURDAY_MIDNIGHT = ZonedDateTime.of(
            2026, 8, 8, 0, 0, 0, 0, java.time.ZoneId.of("Europe/Paris")).toInstant();

    private final WorkingHoursCalendar calendar = new WorkingHoursCalendar();

    // -------------------------------------------------------------------------
    // AC — classement selon disponibilités
    // -------------------------------------------------------------------------

    @Test
    void rank_slotWithFewerAvailableParticipants_isRankedLower() {
        RecordingAvailabilityPort availability = new RecordingAvailabilityPort();
        Instant busyStart = MONDAY_9AM;
        Instant busyEnd = MONDAY_9AM.plus(Duration.ofMinutes(30));
        availability.busy("alice@pivot.test", busyStart, busyEnd);

        BestSlotEngine engine = new BestSlotEngine(availability, calendar);
        List<SlotCandidate> ranked = engine.rank(
                MONDAY_9AM, MONDAY_9AM.plus(Duration.ofHours(2)), 30,
                List.of("alice@pivot.test", "bob@pivot.test"));

        assertThat(ranked).isNotEmpty();
        // The first candidate at 09:00 has only bob available (alice busy) -> conflicted.
        SlotCandidate nineAm = ranked.stream().filter(c -> c.start().equals(MONDAY_9AM)).findFirst().orElseThrow();
        assertThat(nineAm.hasConflict()).isTrue();
        assertThat(nineAm.availableCount()).isEqualTo(1);

        // A fully-available slot must be ranked strictly before the conflicted 09:00 one.
        SlotCandidate best = ranked.get(0);
        assertThat(best.availableCount()).isEqualTo(2);
        assertThat(best.hasConflict()).isFalse();
        assertThat(ranked.indexOf(best)).isLessThan(ranked.indexOf(nineAm));
    }

    @Test
    void rank_noConnectedCalendar_defaultsToAvailable() {
        RecordingAvailabilityPort availability = new RecordingAvailabilityPort();
        BestSlotEngine engine = new BestSlotEngine(availability, calendar);

        List<SlotCandidate> ranked = engine.rank(
                MONDAY_9AM, MONDAY_9AM.plus(Duration.ofMinutes(30)), 30, List.of("nobody-connected@pivot.test"));

        assertThat(ranked).hasSize(1);
        assertThat(ranked.get(0).hasConflict()).isFalse();
        assertThat(ranked.get(0).availableCount()).isEqualTo(1);
    }

    // -------------------------------------------------------------------------
    // AC — déterminisme
    // -------------------------------------------------------------------------

    @Test
    void rank_equalAvailability_isOrderedByStartAscending_deterministically() {
        RecordingAvailabilityPort availability = new RecordingAvailabilityPort();
        BestSlotEngine engine = new BestSlotEngine(availability, calendar);
        List<String> participants = List.of("alice@pivot.test", "bob@pivot.test");

        List<SlotCandidate> first = engine.rank(MONDAY_9AM, MONDAY_9AM.plus(Duration.ofHours(3)), 30, participants);
        List<SlotCandidate> second = engine.rank(MONDAY_9AM, MONDAY_9AM.plus(Duration.ofHours(3)), 30, participants);

        assertThat(first).hasSizeGreaterThan(1);
        assertThat(first.stream().map(SlotCandidate::start).toList())
                .isEqualTo(second.stream().map(SlotCandidate::start).toList())
                .isSorted();
    }

    @Test
    void rank_repeatedInvocations_produceTheSameOrder_notFlaky() {
        RecordingAvailabilityPort availability = new RecordingAvailabilityPort();
        availability.busy("alice@pivot.test", MONDAY_9AM.plus(Duration.ofMinutes(30)), MONDAY_9AM.plus(Duration.ofMinutes(60)));
        BestSlotEngine engine = new BestSlotEngine(availability, calendar);
        List<String> participants = List.of("alice@pivot.test", "bob@pivot.test");

        Set<List<Instant>> observedOrders = new HashSet<>();
        for (int i = 0; i < 20; i++) {
            List<SlotCandidate> ranked = engine.rank(MONDAY_9AM, MONDAY_9AM.plus(Duration.ofHours(3)), 30, participants);
            observedOrders.add(ranked.stream().map(SlotCandidate::start).toList());
        }
        assertThat(observedOrders).hasSize(1);
    }

    // -------------------------------------------------------------------------
    // AC — heures ouvrées / tampon
    // -------------------------------------------------------------------------

    @Test
    void rank_excludesCandidatesOutsideWorkingHours_whenAWorkingHoursCandidateExists() {
        RecordingAvailabilityPort availability = new RecordingAvailabilityPort();
        BestSlotEngine engine = new BestSlotEngine(availability, calendar);

        // Window spans from just before working hours (08:45) through a valid working window.
        Instant windowStart = MONDAY_9AM.minus(Duration.ofMinutes(15));
        List<SlotCandidate> ranked = engine.rank(windowStart, MONDAY_9AM.plus(Duration.ofHours(1)), 30, List.of("a@pivot.test"));

        assertThat(ranked).allMatch(c -> !c.start().isBefore(MONDAY_9AM));
    }

    // -------------------------------------------------------------------------
    // AC — Error : aucun créneau sans conflit / moins mauvais créneau
    // -------------------------------------------------------------------------

    @Test
    void rank_windowEntirelyOutsideWorkingHours_stillProposesAFallbackCandidate_flaggedConflicted() {
        RecordingAvailabilityPort availability = new RecordingAvailabilityPort();
        BestSlotEngine engine = new BestSlotEngine(availability, calendar);

        List<SlotCandidate> ranked = engine.rank(
                SATURDAY_MIDNIGHT, SATURDAY_MIDNIGHT.plus(Duration.ofHours(2)), 30, List.of("a@pivot.test"));

        assertThat(ranked).isNotEmpty();
        assertThat(ranked).allMatch(SlotCandidate::hasConflict);
        assertThat(ranked.get(0).conflictReason()).containsIgnoringCase("hors plage ouvrée");
    }

    @Test
    void rank_windowShorterThanDuration_returnsNoCandidates() {
        RecordingAvailabilityPort availability = new RecordingAvailabilityPort();
        BestSlotEngine engine = new BestSlotEngine(availability, calendar);

        List<SlotCandidate> ranked = engine.rank(
                MONDAY_9AM, MONDAY_9AM.plus(Duration.ofMinutes(10)), 30, List.of("a@pivot.test"));

        assertThat(ranked).isEmpty();
    }

    @Test
    void rank_capsAtMaxCandidates() {
        RecordingAvailabilityPort availability = new RecordingAvailabilityPort();
        BestSlotEngine engine = new BestSlotEngine(availability, calendar);

        List<SlotCandidate> ranked = engine.rank(
                MONDAY_9AM, MONDAY_9AM.plus(Duration.ofHours(8)), 30, List.of("a@pivot.test"));

        assertThat(ranked).hasSizeLessThanOrEqualTo(BestSlotEngine.MAX_CANDIDATES);
    }

    /**
     * Minimal hand-rolled {@link AvailabilityPort} test double — avoids pulling Mockito into a
     * pure-unit test that only needs a trivially simple busy/free lookup.
     */
    private static final class RecordingAvailabilityPort implements AvailabilityPort {

        private final List<String[]> busyKeys = new java.util.ArrayList<>();
        private final List<Instant[]> busyRanges = new java.util.ArrayList<>();

        void busy(final String participantRef, final Instant start, final Instant end) {
            busyKeys.add(new String[] {participantRef});
            busyRanges.add(new Instant[] {start, end});
        }

        @Override
        public boolean isAvailable(final String participantRef, final Instant slotStart, final Instant slotEnd) {
            for (int i = 0; i < busyKeys.size(); i++) {
                if (busyKeys.get(i)[0].equals(participantRef)) {
                    Instant busyStart = busyRanges.get(i)[0];
                    Instant busyEnd = busyRanges.get(i)[1];
                    if (slotStart.isBefore(busyEnd) && busyStart.isBefore(slotEnd)) {
                        return false;
                    }
                }
            }
            return true;
        }
    }
}

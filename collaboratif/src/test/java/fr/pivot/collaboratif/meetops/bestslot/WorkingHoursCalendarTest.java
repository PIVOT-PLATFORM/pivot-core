package fr.pivot.collaboratif.meetops.bestslot;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link WorkingHoursCalendar} (US12.4.1).
 */
class WorkingHoursCalendarTest {

    private static final ZoneId PARIS = ZoneId.of("Europe/Paris");

    private final WorkingHoursCalendar calendar = new WorkingHoursCalendar();

    @Test
    void fitsWorkingHours_weekdayWithinBusinessHours_isTrue() {
        Instant start = ZonedDateTime.of(2026, 8, 3, 10, 0, 0, 0, PARIS).toInstant();
        Instant end = start.plus(Duration.ofMinutes(30));

        assertThat(calendar.fitsWorkingHours(start, end, 15)).isTrue();
    }

    @Test
    void fitsWorkingHours_saturday_isFalse() {
        Instant start = ZonedDateTime.of(2026, 8, 8, 10, 0, 0, 0, PARIS).toInstant();
        Instant end = start.plus(Duration.ofMinutes(30));

        assertThat(calendar.fitsWorkingHours(start, end, 15)).isFalse();
    }

    @Test
    void fitsWorkingHours_fixedHoliday_isFalse() {
        // 2026-07-14 is a Tuesday and a fixed French public holiday.
        Instant start = ZonedDateTime.of(2026, 7, 14, 10, 0, 0, 0, PARIS).toInstant();
        Instant end = start.plus(Duration.ofMinutes(30));

        assertThat(calendar.fitsWorkingHours(start, end, 15)).isFalse();
    }

    @Test
    void fitsWorkingHours_beforeWorkdayStart_isFalse() {
        Instant start = ZonedDateTime.of(2026, 8, 3, 8, 45, 0, 0, PARIS).toInstant();
        Instant end = start.plus(Duration.ofMinutes(30));

        assertThat(calendar.fitsWorkingHours(start, end, 15)).isFalse();
    }

    @Test
    void fitsWorkingHours_endPlusBufferPastWorkdayEnd_isFalse() {
        // 17:50 + 30 min duration = 18:20, + 15 min buffer = 18:35 — past 18:00.
        Instant start = ZonedDateTime.of(2026, 8, 3, 17, 50, 0, 0, PARIS).toInstant();
        Instant end = start.plus(Duration.ofMinutes(30));

        assertThat(calendar.fitsWorkingHours(start, end, 15)).isFalse();
    }

    @Test
    void fitsWorkingHours_endPlusBufferExactlyAtWorkdayEnd_isTrue() {
        // 17:15 + 30 min = 17:45, + 15 min buffer = 18:00 exactly.
        Instant start = ZonedDateTime.of(2026, 8, 3, 17, 15, 0, 0, PARIS).toInstant();
        Instant end = start.plus(Duration.ofMinutes(30));

        assertThat(calendar.fitsWorkingHours(start, end, 15)).isTrue();
    }
}

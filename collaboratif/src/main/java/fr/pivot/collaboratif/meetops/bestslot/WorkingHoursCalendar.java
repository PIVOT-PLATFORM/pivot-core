package fr.pivot.collaboratif.meetops.bestslot;

import org.springframework.stereotype.Component;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.MonthDay;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Set;

/**
 * Resolves "heures ouvrées / weekends / jours fériés de la localité" for the best-slot engine
 * (US12.4.1 AC "Meilleur créneau" (b)).
 *
 * <p><strong>Known, documented simplification</strong> (locality/holiday calendar is not a
 * per-tenant configurable concept yet, out of this sprint's scope): a single hardcoded {@link
 * #ZONE} ({@code Europe/Paris}) and a fixed-date-only French public holiday set ({@link
 * #FIXED_HOLIDAYS}) — movable holidays (Easter Monday, Ascension, Whit Monday) are NOT computed.
 * Working hours are a fixed {@code 09:00–18:00} window, Monday–Friday. Replacing this with a
 * genuine per-tenant/locality calendar service is flagged as a follow-up, not attempted here —
 * mirrors this module's existing practice of documenting rather than silently ignoring known
 * gaps (see e.g. {@code CollaboratifWebSocketConfig}'s "Known gap" notes).
 */
@Component
public class WorkingHoursCalendar {

    private static final ZoneId ZONE = ZoneId.of("Europe/Paris");
    private static final LocalTime WORKDAY_START = LocalTime.of(9, 0);
    private static final LocalTime WORKDAY_END = LocalTime.of(18, 0);

    /** Fixed-date French public holidays only — see class Javadoc for the movable-holiday gap. */
    private static final Set<MonthDay> FIXED_HOLIDAYS = Set.of(
            MonthDay.of(1, 1),
            MonthDay.of(5, 1),
            MonthDay.of(5, 8),
            MonthDay.of(7, 14),
            MonthDay.of(8, 15),
            MonthDay.of(11, 1),
            MonthDay.of(11, 11),
            MonthDay.of(12, 25));

    /**
     * Returns whether the given candidate slot — with an additional trailing buffer — fits
     * entirely within a single working day: not a weekend, not a holiday, and both the slot start
     * and {@code slotEnd + bufferMinutes} fall within {@code 09:00–18:00} local time (US12.4.1 AC
     * (b)/(c) — "heures ouvrées/weekends/jours fériés" + "durée + tampon").
     *
     * @param slotStart      candidate slot start (UTC instant)
     * @param slotEnd        candidate slot end (UTC instant)
     * @param bufferMinutes  trailing buffer required after {@code slotEnd}, in minutes
     * @return {@code true} if the slot (plus buffer) fits within working hours of a single
     *     non-holiday weekday
     */
    public boolean fitsWorkingHours(final Instant slotStart, final Instant slotEnd, final int bufferMinutes) {
        ZonedDateTime start = slotStart.atZone(ZONE);
        ZonedDateTime endWithBuffer = slotEnd.atZone(ZONE).plusMinutes(bufferMinutes);

        if (isWeekend(start) || isHoliday(start.toLocalDate())) {
            return false;
        }
        if (!start.toLocalDate().equals(endWithBuffer.toLocalDate())) {
            // A slot that spills into the next calendar day is never "working hours" — even if
            // that next day is itself a working day, this candidate would straddle the night.
            return false;
        }
        LocalTime startTime = start.toLocalTime();
        LocalTime endTime = endWithBuffer.toLocalTime();
        return !startTime.isBefore(WORKDAY_START) && !endTime.isAfter(WORKDAY_END);
    }

    /**
     * Returns whether the given instant falls on a Saturday or Sunday, in the calendar's local
     * time zone.
     *
     * @param zonedDateTime the instant, already zoned
     * @return {@code true} if Saturday or Sunday
     */
    private boolean isWeekend(final ZonedDateTime zonedDateTime) {
        DayOfWeek dayOfWeek = zonedDateTime.getDayOfWeek();
        return dayOfWeek == DayOfWeek.SATURDAY || dayOfWeek == DayOfWeek.SUNDAY;
    }

    /**
     * Returns whether the given local date is one of the fixed-date French public holidays.
     *
     * @param date the local date to check
     * @return {@code true} if a recognized fixed holiday
     */
    private boolean isHoliday(final LocalDate date) {
        return FIXED_HOLIDAYS.contains(MonthDay.from(date));
    }
}

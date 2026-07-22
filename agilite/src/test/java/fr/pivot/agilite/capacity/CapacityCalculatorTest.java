package fr.pivot.agilite.capacity;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.Month;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.offset;

/**
 * Unit tests for {@link CapacityCalculator} — pure, no Spring/database context (US11.1.2).
 */
class CapacityCalculatorTest {

    // Monday 2026-01-05 .. Friday 2026-01-09: 5 working days, 0 weekend days.
    private static final LocalDate MON = LocalDate.of(2026, Month.JANUARY, 5);
    private static final LocalDate FRI = LocalDate.of(2026, Month.JANUARY, 9);

    @Test
    void countWorkingDays_excludesOnlyWeekends() {
        // Mon 2026-01-05 .. Sun 2026-01-11 (a full week + weekend) = 5 working days.
        assertThat(CapacityCalculator.countWorkingDays(MON, MON.plusDays(6))).isEqualTo(5);
    }

    @Test
    void countWorkingDays_singleWeekendDay_returnsZero() {
        LocalDate saturday = LocalDate.of(2026, Month.JANUARY, 10);
        assertThat(CapacityCalculator.countWorkingDays(saturday, saturday)).isZero();
    }

    @Test
    void summarize_noExcludedNoAbsence_fullCapacity() {
        List<CapacityCalculator.MemberInput> members = List.of(
                new CapacityCalculator.MemberInput(false, 100, List.of(), null),
                new CapacityCalculator.MemberInput(false, 100, List.of(), null));

        CapacityCalculator.Summary summary = CapacityCalculator.summarize(MON, FRI, members, null);

        assertThat(summary.workingDays()).isEqualTo(5);
        assertThat(summary.memberCount()).isEqualTo(2);
        assertThat(summary.totalAbsenceDays()).isZero();
        assertThat(summary.netCapacityDays()).isEqualTo(10.0, offset(0.001));
        assertThat(summary.netCapacityPoints()).isNull();
        assertThat(summary.isProvisional()).isTrue();
    }

    @Test
    void summarize_excludedMember_doesNotContribute() {
        List<CapacityCalculator.MemberInput> members = List.of(
                new CapacityCalculator.MemberInput(false, 100, List.of(), null),
                new CapacityCalculator.MemberInput(true, 100, List.of(), null));

        CapacityCalculator.Summary summary = CapacityCalculator.summarize(MON, FRI, members, null);

        assertThat(summary.memberCount()).isEqualTo(1);
        assertThat(summary.netCapacityDays()).isEqualTo(5.0, offset(0.001));
    }

    @Test
    void summarize_partialAvailability_scalesCapacity() {
        List<CapacityCalculator.MemberInput> members =
                List.of(new CapacityCalculator.MemberInput(false, 50, List.of(), null));

        CapacityCalculator.Summary summary = CapacityCalculator.summarize(MON, FRI, members, null);

        assertThat(summary.netCapacityDays()).isEqualTo(2.5, offset(0.001));
    }

    @Test
    void summarize_absenceOverlappingPeriod_deductsWorkingDaysOnly() {
        // Absence Wed-Sun (2026-01-07..2026-01-11): only Wed/Thu/Fri (3 working days) overlap
        // the Mon-Fri event period.
        CapacityCalculator.AbsenceRange absence = new CapacityCalculator.AbsenceRange(
                LocalDate.of(2026, Month.JANUARY, 7), LocalDate.of(2026, Month.JANUARY, 11));
        List<CapacityCalculator.MemberInput> members =
                List.of(new CapacityCalculator.MemberInput(false, 100, List.of(absence), null));

        CapacityCalculator.Summary summary = CapacityCalculator.summarize(MON, FRI, members, null);

        assertThat(summary.totalAbsenceDays()).isEqualTo(3);
        assertThat(summary.netCapacityDays()).isEqualTo(2.0, offset(0.001));
    }

    @Test
    void summarize_pointsPerDayProvided_computesNetCapacityPoints() {
        List<CapacityCalculator.MemberInput> members =
                List.of(new CapacityCalculator.MemberInput(false, 100, List.of(), null));

        CapacityCalculator.Summary summary = CapacityCalculator.summarize(MON, FRI, members, 2.0);

        assertThat(summary.netCapacityPoints()).isEqualTo(10.0, offset(0.001));
    }

    @Test
    void aggregate_noChildren_returnsZeroDaysAndNullPoints() {
        CapacityCalculator.Summary summary = CapacityCalculator.aggregate(MON, FRI, List.of());

        assertThat(summary.memberCount()).isZero();
        assertThat(summary.netCapacityDays()).isZero();
        assertThat(summary.netCapacityPoints()).isNull();
        assertThat(summary.isProvisional()).isTrue();
    }

    @Test
    void aggregate_withChildren_sumsDaysAndPoints() {
        CapacityCalculator.Summary child1 = new CapacityCalculator.Summary(5, 5, 2, 0, 10.0, 20.0, true);
        CapacityCalculator.Summary child2 = new CapacityCalculator.Summary(5, 5, 1, 1, 4.0, 8.0, true);

        CapacityCalculator.Summary aggregated = CapacityCalculator.aggregate(MON, FRI, List.of(child1, child2));

        assertThat(aggregated.memberCount()).isEqualTo(3);
        assertThat(aggregated.totalAbsenceDays()).isEqualTo(1);
        assertThat(aggregated.netCapacityDays()).isEqualTo(14.0, offset(0.001));
        assertThat(aggregated.netCapacityPoints()).isEqualTo(28.0, offset(0.001));
    }

    @Test
    void aggregate_someChildrenWithoutPoints_netCapacityPointsIsNull() {
        CapacityCalculator.Summary childWithPoints = new CapacityCalculator.Summary(5, 5, 1, 0, 5.0, 10.0, true);
        CapacityCalculator.Summary childWithoutPoints = new CapacityCalculator.Summary(5, 5, 1, 0, 5.0, null, true);

        CapacityCalculator.Summary aggregated =
                CapacityCalculator.aggregate(MON, FRI, List.of(childWithPoints, childWithoutPoints));

        assertThat(aggregated.netCapacityPoints()).isNull();
    }

    @Test
    void aggregate_notAllChildrenProvisional_aggregateIsNotProvisional() {
        CapacityCalculator.Summary provisional = new CapacityCalculator.Summary(5, 5, 1, 0, 5.0, null, true);
        CapacityCalculator.Summary notProvisional = new CapacityCalculator.Summary(5, 5, 1, 0, 5.0, null, false);

        CapacityCalculator.Summary aggregated =
                CapacityCalculator.aggregate(MON, FRI, List.of(provisional, notProvisional));

        assertThat(aggregated.isProvisional()).isFalse();
    }

    // ── Full F11.6 engine overload (US11.6.1/US11.6.2/US11.6.5) ──────────────────────────────

    @Test
    void countWorkingDays_withHolidays_excludesHolidayAlongsideWeekends() {
        // Wed 2026-01-07 is a tenant holiday inside the Mon-Fri range => 4 working days.
        LocalDate holiday = LocalDate.of(2026, Month.JANUARY, 7);
        assertThat(CapacityCalculator.countWorkingDays(MON, FRI, Set.of(holiday))).isEqualTo(4);
    }

    @Test
    void summarizeFull_noConfiguration_matchesS20Behavior() {
        List<CapacityCalculator.MemberInput> members =
                List.of(new CapacityCalculator.MemberInput(false, 100, List.of(), null));

        CapacityCalculator.Summary summary =
                CapacityCalculator.summarize(MON, FRI, members, null, Set.of(), 100, true);

        assertThat(summary.workingDays()).isEqualTo(5);
        assertThat(summary.netCapacityDays()).isEqualTo(5.0, offset(0.001));
        assertThat(summary.isProvisional()).isTrue();
    }

    @Test
    void summarizeFull_eventFocusFactor_scalesNetCapacity() {
        List<CapacityCalculator.MemberInput> members =
                List.of(new CapacityCalculator.MemberInput(false, 100, List.of(), null));

        CapacityCalculator.Summary summary =
                CapacityCalculator.summarize(MON, FRI, members, null, Set.of(), 70, false);

        assertThat(summary.netCapacityDays()).isEqualTo(3.5, offset(0.001));
        assertThat(summary.isProvisional()).isFalse();
    }

    @Test
    void summarizeFull_memberFocusOverride_takesPrecedenceOverEventFocus() {
        List<CapacityCalculator.MemberInput> members =
                List.of(new CapacityCalculator.MemberInput(false, 100, List.of(), 50));

        CapacityCalculator.Summary summary =
                CapacityCalculator.summarize(MON, FRI, members, null, Set.of(), 70, false);

        // Member override (50%) wins over the event-level 70%.
        assertThat(summary.netCapacityDays()).isEqualTo(2.5, offset(0.001));
    }

    @Test
    void summarizeFull_holidayReducesWorkingDaysAndCapacity() {
        LocalDate holiday = LocalDate.of(2026, Month.JANUARY, 7);
        List<CapacityCalculator.MemberInput> members =
                List.of(new CapacityCalculator.MemberInput(false, 100, List.of(), null));

        CapacityCalculator.Summary summary =
                CapacityCalculator.summarize(MON, FRI, members, null, Set.of(holiday), 100, false);

        assertThat(summary.workingDays()).isEqualTo(4);
        assertThat(summary.netCapacityDays()).isEqualTo(4.0, offset(0.001));
    }
}

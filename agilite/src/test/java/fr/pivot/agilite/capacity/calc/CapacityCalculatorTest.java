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
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link CapacityCalculator} (E11 — capacity planning) — pure computation, no
 * Spring context.
 *
 * <p>The first sections port every case of the PouetPouet POC's {@code capacity.test.ts}
 * (adapted: the POC's {@code hours}/{@code hoursPerDay} concept has no E11 equivalent — dropped
 * from the schema entirely — and {@code netPersonDays} is split here into the focus-free {@code
 * joursHommeNets} and the focus-applied {@code capaciteNette}, per the E11 design). The later
 * sections cover the E11-only additions: holidays, maturity levels, engagement margin, the
 * rolling-window velocity/CV forecast, focus precedence/validation, PI consolidation, and depth-2.
 */
class CapacityCalculatorTest {

    private static final Set<Integer> MON_FRI = Set.of(1, 2, 3, 4, 5);
    private static final Set<LocalDate> NO_HOLIDAYS = Set.of();

    // ── Fixtures ────────────────────────────────────────────────────────────────

    /** Builds a 2-week Mon-Fri sprint (2026-06-01 .. 2026-06-12 = 10 working days), focus 0.8. */
    private static EventBuilder baseEvent() {
        return new EventBuilder()
                .startDate(LocalDate.of(2026, 6, 1))
                .endDate(LocalDate.of(2026, 6, 12))
                .workingDays(MON_FRI)
                .holidays(NO_HOLIDAYS)
                .focusFactor(0.8);
    }

    private static MemberBuilder baseMember() {
        return new MemberBuilder().id("m-1").role("Dev").quotite(1).position(0);
    }

    private static CapacityAbsenceInput absence(final String start, final String end, final double fraction) {
        return new CapacityAbsenceInput(LocalDate.parse(start), LocalDate.parse(end), fraction);
    }

    /** Small mutable builder over {@link CapacityEventInput} — the record has 12 components. */
    private static final class EventBuilder {
        private LocalDate startDate;
        private LocalDate endDate;
        private Set<Integer> workingDays = MON_FRI;
        private Set<LocalDate> holidays = NO_HOLIDAYS;
        private Double focusFactor;
        private Map<String, Double> roleFocusFactors = new HashMap<>();
        private Double margeSecurite;
        private Double pointsPerDay;
        private CapacityMaturityLevel maturityLevel;
        private Double committedPoints;
        private Double completedPoints;
        private List<CapacityMemberInput> members = new ArrayList<>();

        EventBuilder startDate(final LocalDate v) {
            this.startDate = v;
            return this;
        }

        EventBuilder endDate(final LocalDate v) {
            this.endDate = v;
            return this;
        }

        EventBuilder workingDays(final Set<Integer> v) {
            this.workingDays = v;
            return this;
        }

        EventBuilder holidays(final Set<LocalDate> v) {
            this.holidays = v;
            return this;
        }

        EventBuilder focusFactor(final Double v) {
            this.focusFactor = v;
            return this;
        }

        EventBuilder roleFocusFactors(final Map<String, Double> v) {
            this.roleFocusFactors = v;
            return this;
        }

        EventBuilder margeSecurite(final Double v) {
            this.margeSecurite = v;
            return this;
        }

        EventBuilder pointsPerDay(final Double v) {
            this.pointsPerDay = v;
            return this;
        }

        EventBuilder maturityLevel(final CapacityMaturityLevel v) {
            this.maturityLevel = v;
            return this;
        }

        EventBuilder committedPoints(final Double v) {
            this.committedPoints = v;
            return this;
        }

        EventBuilder completedPoints(final Double v) {
            this.completedPoints = v;
            return this;
        }

        EventBuilder members(final CapacityMemberInput... v) {
            this.members = Arrays.asList(v);
            return this;
        }

        CapacityEventInput build() {
            return new CapacityEventInput(
                    startDate, endDate, workingDays, holidays, focusFactor, roleFocusFactors, margeSecurite,
                    pointsPerDay, maturityLevel, committedPoints, completedPoints, members);
        }
    }

    /** Small mutable builder over {@link CapacityMemberInput} — the record has 7 components. */
    private static final class MemberBuilder {
        private String id = "m-1";
        private double quotite = 1;
        private Double focusFactor;
        private String role;
        private boolean excluded;
        private int position;
        private List<CapacityAbsenceInput> absences = new ArrayList<>();

        MemberBuilder id(final String v) {
            this.id = v;
            return this;
        }

        MemberBuilder quotite(final double v) {
            this.quotite = v;
            return this;
        }

        MemberBuilder focusFactor(final Double v) {
            this.focusFactor = v;
            return this;
        }

        MemberBuilder role(final String v) {
            this.role = v;
            return this;
        }

        MemberBuilder excluded(final boolean v) {
            this.excluded = v;
            return this;
        }

        MemberBuilder position(final int v) {
            this.position = v;
            return this;
        }

        MemberBuilder absences(final CapacityAbsenceInput... v) {
            this.absences = Arrays.asList(v);
            return this;
        }

        CapacityMemberInput build() {
            return new CapacityMemberInput(id, quotite, focusFactor, role, excluded, position, absences);
        }
    }

    // ── countWorkingDays (ported from capacity.test.ts) ────────────────────────

    @Test
    void countWorkingDays_standardTwoWeekSprint_countsTen() {
        assertThat(CapacityCalculator.countWorkingDays(
                LocalDate.of(2026, 6, 1), LocalDate.of(2026, 6, 12), MON_FRI, NO_HOLIDAYS)).isEqualTo(10);
    }

    @Test
    void countWorkingDays_singleMonday_countsOne() {
        assertThat(CapacityCalculator.countWorkingDays(
                LocalDate.of(2026, 6, 1), LocalDate.of(2026, 6, 1), MON_FRI, NO_HOLIDAYS)).isEqualTo(1);
    }

    @Test
    void countWorkingDays_singleSaturdayOnMonFriSchedule_countsZero() {
        assertThat(CapacityCalculator.countWorkingDays(
                LocalDate.of(2026, 6, 6), LocalDate.of(2026, 6, 6), MON_FRI, NO_HOLIDAYS)).isEqualTo(0);
    }

    @Test
    void countWorkingDays_endBeforeStart_returnsZero() {
        assertThat(CapacityCalculator.countWorkingDays(
                LocalDate.of(2026, 6, 12), LocalDate.of(2026, 6, 1), MON_FRI, NO_HOLIDAYS)).isEqualTo(0);
    }

    @Test
    void countWorkingDays_sameDayNotWorkingDay_returnsZero() {
        // 2026-06-07 is a Sunday
        assertThat(CapacityCalculator.countWorkingDays(
                LocalDate.of(2026, 6, 7), LocalDate.of(2026, 6, 7), MON_FRI, NO_HOLIDAYS)).isEqualTo(0);
    }

    @Test
    void countWorkingDays_allWeekdaysIncluded_countsSeven() {
        assertThat(CapacityCalculator.countWorkingDays(
                LocalDate.of(2026, 6, 1), LocalDate.of(2026, 6, 7), Set.of(0, 1, 2, 3, 4, 5, 6), NO_HOLIDAYS))
                .isEqualTo(7);
    }

    @Test
    void countWorkingDays_onlySaturdaySchedule_countsOne() {
        // 2026-06-01 (Mon) .. 2026-06-07 (Sun) has one Saturday (2026-06-06)
        assertThat(CapacityCalculator.countWorkingDays(
                LocalDate.of(2026, 6, 1), LocalDate.of(2026, 6, 7), Set.of(6), NO_HOLIDAYS)).isEqualTo(1);
    }

    // ── absenceWorkingDays (ported) ──────────────────────────────────────────────

    @Test
    void absenceWorkingDays_fullyWithinEvent_countsExactly() {
        assertThat(CapacityCalculator.absenceWorkingDays(
                absence("2026-06-02", "2026-06-02", 1),
                LocalDate.of(2026, 6, 1), LocalDate.of(2026, 6, 12), MON_FRI, NO_HOLIDAYS))
                .isEqualTo(1);
    }

    @Test
    void absenceWorkingDays_startsBeforeEvent_clipsToEventStart() {
        // Event starts 2026-06-01 (Mon); Mon->Fri = 5 working days
        assertThat(CapacityCalculator.absenceWorkingDays(
                absence("2026-05-25", "2026-06-05", 1),
                LocalDate.of(2026, 6, 1), LocalDate.of(2026, 6, 12), MON_FRI, NO_HOLIDAYS))
                .isEqualTo(5);
    }

    @Test
    void absenceWorkingDays_extendsBeyondEvent_clipsToEventEnd() {
        // Event ends 2026-06-12; Wed+Thu+Fri = 3 days
        assertThat(CapacityCalculator.absenceWorkingDays(
                absence("2026-06-10", "2026-06-30", 1),
                LocalDate.of(2026, 6, 1), LocalDate.of(2026, 6, 12), MON_FRI, NO_HOLIDAYS))
                .isEqualTo(3);
    }

    @Test
    void absenceWorkingDays_entirelyOutsideEvent_returnsZero() {
        assertThat(CapacityCalculator.absenceWorkingDays(
                absence("2026-06-15", "2026-06-20", 1),
                LocalDate.of(2026, 6, 1), LocalDate.of(2026, 6, 12), MON_FRI, NO_HOLIDAYS))
                .isEqualTo(0);
    }

    // ── computeMemberCapacity (ported, hours dropped, netPersonDays split) ──────

    @Test
    void computeMemberCapacity_fullTimeNoAbsences_computesJoursHommeNetsAndCapaciteNette() {
        MemberCapacityResult result = CapacityCalculator.computeMemberCapacity(baseMember().build(), baseEvent().build());

        assertThat(result.absentWorkingDays()).isEqualTo(0);
        assertThat(result.joursHommeNets()).isEqualTo(10);
        assertThat(result.effectiveFocus()).isEqualTo(0.8);
        assertThat(result.capaciteNette()).isEqualTo(8); // 10 x 0.8
        assertThat(result.points()).isNull();
        assertThat(result.engagementRecommande()).isEqualTo(6.8); // 8 x (1 - 0.15 default margin)
    }

    @Test
    void computeMemberCapacity_pointsPerDaySet_computesFromFocusFreeDays() {
        CapacityEventInput event = baseEvent().pointsPerDay(2.0).build();

        MemberCapacityResult result = CapacityCalculator.computeMemberCapacity(baseMember().build(), event);

        assertThat(result.points()).isEqualTo(20); // 10 (joursHommeNets, not capaciteNette) x 2
    }

    @Test
    void computeMemberCapacity_absenceDeductsWorkingDaysWeightedByFraction() {
        CapacityMemberInput member = baseMember()
                .absences(absence("2026-06-01", "2026-06-05", 1))
                .build();

        MemberCapacityResult result = CapacityCalculator.computeMemberCapacity(member, baseEvent().build());

        assertThat(result.absentWorkingDays()).isEqualTo(5);
        assertThat(result.joursHommeNets()).isEqualTo(5);
        assertThat(result.capaciteNette()).isEqualTo(4); // 5 x 0.8
    }

    @Test
    void computeMemberCapacity_halfDayAbsence_deductsHalf() {
        CapacityMemberInput member = baseMember()
                .absences(absence("2026-06-01", "2026-06-02", 0.5))
                .build();

        MemberCapacityResult result = CapacityCalculator.computeMemberCapacity(member, baseEvent().build());

        assertThat(result.absentWorkingDays()).isEqualTo(1); // 2 days x 0.5
        assertThat(result.joursHommeNets()).isEqualTo(9);
    }

    @Test
    void computeMemberCapacity_memberFocusOverridesEventFocus() {
        CapacityMemberInput member = baseMember().focusFactor(0.5).build();

        MemberCapacityResult result = CapacityCalculator.computeMemberCapacity(member, baseEvent().build());

        assertThat(result.effectiveFocus()).isEqualTo(0.5);
        assertThat(result.capaciteNette()).isEqualTo(5); // 10 x 0.5
    }

    @Test
    void computeMemberCapacity_halfFte_halvesJoursHommeNets() {
        CapacityMemberInput member = baseMember().quotite(0.5).build();

        MemberCapacityResult result = CapacityCalculator.computeMemberCapacity(member, baseEvent().build());

        assertThat(result.joursHommeNets()).isEqualTo(5); // 10 x 0.5
        assertThat(result.capaciteNette()).isEqualTo(4); // 5 x 0.8
    }

    @Test
    void computeMemberCapacity_joursHommeNetsNeverNegative() {
        CapacityMemberInput member = baseMember()
                .absences(absence("2026-05-01", "2026-06-30", 1))
                .build();

        MemberCapacityResult result = CapacityCalculator.computeMemberCapacity(member, baseEvent().build());

        assertThat(result.joursHommeNets()).isEqualTo(0);
        assertThat(result.capaciteNette()).isEqualTo(0);
    }

    // ── computeEventCapacity (ported) ───────────────────────────────────────────

    @Test
    void computeEventCapacity_aggregatesTwoMembers() {
        CapacityEventInput event = baseEvent()
                .pointsPerDay(2.0)
                .members(
                        baseMember().id("m1").position(0).build(),
                        baseMember().id("m2").position(1).build())
                .build();

        EventCapacityResult result = CapacityCalculator.computeEventCapacity(event);

        assertThat(result.totalWorkingDays()).isEqualTo(10);
        assertThat(result.totalJoursHommeNets()).isEqualTo(20);
        assertThat(result.totalCapaciteNette()).isEqualTo(16);
        assertThat(result.totalPoints()).isEqualTo(40);
    }

    @Test
    void computeEventCapacity_loadRatioIsCommittedOverTotalPoints() {
        CapacityEventInput event = baseEvent()
                .pointsPerDay(2.0)
                .committedPoints(50.0)
                .members(baseMember().build())
                .build();

        EventCapacityResult result = CapacityCalculator.computeEventCapacity(event);

        assertThat(result.loadRatio()).isEqualTo(2.5); // 50 / 20
    }

    @Test
    void computeEventCapacity_loadRatioNullWithoutPointsPerDay() {
        CapacityEventInput event = baseEvent().committedPoints(30.0).members(baseMember().build()).build();

        EventCapacityResult result = CapacityCalculator.computeEventCapacity(event);

        assertThat(result.loadRatio()).isNull();
    }

    @Test
    void computeEventCapacity_predictabilityIsCompletedOverCommitted() {
        CapacityEventInput event = baseEvent().committedPoints(20.0).completedPoints(18.0).build();

        EventCapacityResult result = CapacityCalculator.computeEventCapacity(event);

        assertThat(result.predictability()).isEqualTo(0.9);
    }

    @Test
    void computeEventCapacity_predictabilityNullWhenCommittedIsZero() {
        CapacityEventInput event = baseEvent().committedPoints(0.0).completedPoints(5.0).build();

        EventCapacityResult result = CapacityCalculator.computeEventCapacity(event);

        assertThat(result.predictability()).isNull();
    }

    @Test
    void computeEventCapacity_sortsMembersByPositionBeforeAggregating() {
        CapacityEventInput event = baseEvent()
                .members(
                        baseMember().id("m2").position(1).build(),
                        baseMember().id("m1").position(0).build())
                .build();

        EventCapacityResult result = CapacityCalculator.computeEventCapacity(event);

        assertThat(result.members().get(0).memberId()).isEqualTo("m1");
        assertThat(result.members().get(1).memberId()).isEqualTo("m2");
    }

    @Test
    void computeEventCapacity_excludedMemberOmittedFromTotalsButListed() {
        CapacityEventInput event = baseEvent()
                .pointsPerDay(2.0)
                .members(
                        baseMember().id("m1").position(0).build(),
                        baseMember().id("m2").position(1).excluded(true).build())
                .build();

        EventCapacityResult result = CapacityCalculator.computeEventCapacity(event);

        assertThat(result.members()).hasSize(2);
        assertThat(result.totalJoursHommeNets()).isEqualTo(10); // only m1
        assertThat(result.totalPoints()).isEqualTo(20); // only m1
    }

    // ── E11: holidays (injected, new vs. POC) ───────────────────────────────────

    @Test
    void countWorkingDays_excludesInjectedHoliday() {
        // 2026-06-01 (Mon) .. 2026-06-05 (Fri) = 5 working days, minus a Wednesday holiday
        Set<LocalDate> holidays = Set.of(LocalDate.of(2026, 6, 3));

        assertThat(CapacityCalculator.countWorkingDays(
                LocalDate.of(2026, 6, 1), LocalDate.of(2026, 6, 5), MON_FRI, holidays)).isEqualTo(4);
    }

    @Test
    void computeMemberCapacity_holidayReducesJoursHommeNets() {
        CapacityEventInput event = baseEvent()
                .startDate(LocalDate.of(2026, 6, 1))
                .endDate(LocalDate.of(2026, 6, 5))
                .holidays(Set.of(LocalDate.of(2026, 6, 3)))
                .build();

        MemberCapacityResult result = CapacityCalculator.computeMemberCapacity(baseMember().build(), event);

        assertThat(result.joursHommeNets()).isEqualTo(4);
    }

    // ── E11: maturity levels ─────────────────────────────────────────────────────

    @Test
    void maturityProfile_forming_is60_20_080() {
        assertThat(CapacityCalculator.maturityProfile(CapacityMaturityLevel.FORMING))
                .isEqualTo(new MaturityProfile(0.60, 0.20, 0.80));
    }

    @Test
    void maturityProfile_norming_is70_10_090() {
        assertThat(CapacityCalculator.maturityProfile(CapacityMaturityLevel.NORMING))
                .isEqualTo(new MaturityProfile(0.70, 0.10, 0.90));
    }

    @Test
    void maturityProfile_performing_is80_5_095() {
        assertThat(CapacityCalculator.maturityProfile(CapacityMaturityLevel.PERFORMING))
                .isEqualTo(new MaturityProfile(0.80, 0.05, 0.95));
    }

    @Test
    void maturityProfile_unsetNull_defaultsTo70_15_085() {
        assertThat(CapacityCalculator.maturityProfile(null)).isEqualTo(new MaturityProfile(0.70, 0.15, 0.85));
    }

    @Test
    void computeMemberCapacity_usesMaturityDefaultFocus_whenNoEventOrMemberFocus() {
        CapacityEventInput event = baseEvent().focusFactor(null).maturityLevel(CapacityMaturityLevel.PERFORMING).build();

        MemberCapacityResult result = CapacityCalculator.computeMemberCapacity(baseMember().build(), event);

        assertThat(result.effectiveFocus()).isEqualTo(0.80);
    }

    @Test
    void computeMemberCapacity_usesMaturityDefaultMargin_whenNoEventMargin() {
        CapacityEventInput event = baseEvent().maturityLevel(CapacityMaturityLevel.FORMING).build();

        MemberCapacityResult result = CapacityCalculator.computeMemberCapacity(baseMember().build(), event);

        // capaciteNette = 10 x 0.8 = 8 ; margin from FORMING = 0.20 -> 8 x 0.8 = 6.4
        assertThat(result.engagementRecommande()).isEqualTo(6.4);
    }

    @Test
    void maturityAdjustedCapacity_appliesVelocityMultiplier() {
        assertThat(CapacityCalculator.maturityAdjustedCapacity(10.0, CapacityMaturityLevel.FORMING)).isEqualTo(8.0);
        assertThat(CapacityCalculator.maturityAdjustedCapacity(10.0, null)).isEqualTo(8.5);
    }

    // ── E11: engagement margin ───────────────────────────────────────────────────

    @Test
    void computeMemberCapacity_explicitMarginOverridesMaturityDefault() {
        CapacityEventInput event = baseEvent().margeSecurite(0.3).build();

        MemberCapacityResult result = CapacityCalculator.computeMemberCapacity(baseMember().build(), event);

        assertThat(result.engagementRecommande()).isEqualTo(5.6); // 8 x (1 - 0.3)
    }

    // ── E11: focus precedence (member > role > event > maturity) ───────────────

    @Test
    void resolveFocusFactor_memberOverride_winsOverEverything() {
        CapacityMemberInput member = baseMember().focusFactor(0.9).role("Dev").build();
        CapacityEventInput event = baseEvent()
                .focusFactor(0.5)
                .roleFocusFactors(Map.of("Dev", 0.6))
                .maturityLevel(CapacityMaturityLevel.FORMING)
                .build();

        assertThat(CapacityCalculator.resolveFocusFactor(member, event)).isEqualTo(0.9);
    }

    @Test
    void resolveFocusFactor_roleOverride_winsOverEventAndMaturity_whenNoMemberOverride() {
        CapacityMemberInput member = baseMember().role("Dev").build();
        CapacityEventInput event = baseEvent()
                .focusFactor(0.5)
                .roleFocusFactors(Map.of("Dev", 0.6))
                .maturityLevel(CapacityMaturityLevel.FORMING)
                .build();

        assertThat(CapacityCalculator.resolveFocusFactor(member, event)).isEqualTo(0.6);
    }

    @Test
    void resolveFocusFactor_eventDefault_winsOverMaturity_whenNoMemberOrRoleOverride() {
        CapacityMemberInput member = baseMember().role("Dev").build();
        CapacityEventInput event = baseEvent().focusFactor(0.5).maturityLevel(CapacityMaturityLevel.FORMING).build();

        assertThat(CapacityCalculator.resolveFocusFactor(member, event)).isEqualTo(0.5);
    }

    @Test
    void resolveFocusFactor_fallsBackToMaturityDefault_whenNothingElseSet() {
        CapacityMemberInput member = baseMember().role("Dev").build();
        CapacityEventInput event = baseEvent().focusFactor(null).maturityLevel(CapacityMaturityLevel.NORMING).build();

        assertThat(CapacityCalculator.resolveFocusFactor(member, event)).isEqualTo(0.70);
    }

    // ── E11: focus out-of-range rejection ───────────────────────────────────────

    @Test
    void resolveFocusFactor_memberFocusAboveOne_throws() {
        CapacityMemberInput member = baseMember().focusFactor(1.5).build();

        assertThatThrownBy(() -> CapacityCalculator.resolveFocusFactor(member, baseEvent().build()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void resolveFocusFactor_roleFocusBelowZero_throws() {
        CapacityMemberInput member = baseMember().role("Dev").build();
        CapacityEventInput event = baseEvent().focusFactor(null).roleFocusFactors(Map.of("Dev", -0.1)).build();

        assertThatThrownBy(() -> CapacityCalculator.resolveFocusFactor(member, event))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void resolveFocusFactor_eventFocusAboveOne_throws() {
        CapacityMemberInput member = baseMember().build();
        CapacityEventInput event = baseEvent().focusFactor(2.0).build();

        assertThatThrownBy(() -> CapacityCalculator.resolveFocusFactor(member, event))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ── E11: PI consolidation, ± SAFe IP exclusion, depth-2 ─────────────────────

    @Test
    void consolidatePi_safeIpExclusionOn_excludesIpSprint() {
        EventCapacityResult regularSprint = CapacityCalculator.computeEventCapacity(
                baseEvent().pointsPerDay(2.0).members(baseMember().build()).build());
        EventCapacityResult ipSprint = CapacityCalculator.computeEventCapacity(
                baseEvent().pointsPerDay(2.0).members(baseMember().id("m2").build()).build());

        PiCapacityResult result = CapacityCalculator.consolidatePi(
                List.of(new SprintContribution(regularSprint, false), new SprintContribution(ipSprint, true)), true);

        assertThat(result.includedSprintCount()).isEqualTo(1);
        assertThat(result.excludedIpSprintCount()).isEqualTo(1);
        assertThat(result.totalPoints()).isEqualTo(regularSprint.totalPoints());
    }

    @Test
    void consolidatePi_safeIpExclusionOff_includesEverySprint() {
        EventCapacityResult regularSprint = CapacityCalculator.computeEventCapacity(
                baseEvent().pointsPerDay(2.0).members(baseMember().build()).build());
        EventCapacityResult ipSprint = CapacityCalculator.computeEventCapacity(
                baseEvent().pointsPerDay(2.0).members(baseMember().id("m2").build()).build());

        PiCapacityResult result = CapacityCalculator.consolidatePi(
                List.of(new SprintContribution(regularSprint, false), new SprintContribution(ipSprint, true)), false);

        assertThat(result.includedSprintCount()).isEqualTo(2);
        assertThat(result.excludedIpSprintCount()).isEqualTo(0);
        assertThat(result.totalPoints()).isEqualTo(regularSprint.totalPoints() + ipSprint.totalPoints());
    }

    @Test
    void requireMaxDepth_parentHasNoParent_doesNotThrow() {
        assertThatCode(() -> CapacityCalculator.requireMaxDepth(false)).doesNotThrowAnyException();
    }

    @Test
    void requireMaxDepth_parentAlreadyHasParent_throws() {
        assertThatThrownBy(() -> CapacityCalculator.requireMaxDepth(true))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ── E11: velocity forecast (rolling window + CV) ────────────────────────────

    @Test
    void forecastVelocity_emptyHistory_returnsNull() {
        assertThat(CapacityCalculator.forecastVelocity(List.of(), 3)).isNull();
    }

    @Test
    void forecastVelocity_allEntriesEmpty_returnsNull() {
        List<Double> history = new ArrayList<>();
        history.add(null);
        history.add(null);

        assertThat(CapacityCalculator.forecastVelocity(history, 3)).isNull();
    }

    @Test
    void forecastVelocity_lowVariability_tightensInterval() {
        // Last 3: 20, 21, 19 -> mean 20, low CV
        VelocityForecast forecast = CapacityCalculator.forecastVelocity(List.of(20.0, 21.0, 19.0), 3);

        assertThat(forecast.sampleSize()).isEqualTo(3);
        assertThat(forecast.mean()).isEqualTo(20.0);
        assertThat(forecast.coefficientOfVariation()).isLessThanOrEqualTo(0.25);
        assertThat(forecast.widened()).isFalse();
        assertThat(forecast.upperBound() - forecast.lowerBound()).isLessThan(4 * forecast.stdDev());
    }

    @Test
    void forecastVelocity_highVariability_widensInterval() {
        // Last 3: 5, 30, 10 -> high spread relative to mean
        VelocityForecast forecast = CapacityCalculator.forecastVelocity(List.of(5.0, 30.0, 10.0), 3);

        assertThat(forecast.coefficientOfVariation()).isGreaterThan(0.25);
        assertThat(forecast.widened()).isTrue();
    }

    @Test
    void forecastVelocity_windowLargerThanHistory_usesWholeHistory() {
        VelocityForecast forecast = CapacityCalculator.forecastVelocity(List.of(10.0, 20.0), 5);

        assertThat(forecast.sampleSize()).isEqualTo(2);
        assertThat(forecast.mean()).isEqualTo(15.0);
    }

    @Test
    void forecastVelocity_windowSmallerThanHistory_onlyConsidersMostRecent() {
        // 5 sprints of history, window=3 -> only the last 3 (20, 21, 19) matter, not the first two
        VelocityForecast forecast = CapacityCalculator.forecastVelocity(List.of(1.0, 1.0, 20.0, 21.0, 19.0), 3);

        assertThat(forecast.sampleSize()).isEqualTo(3);
        assertThat(forecast.mean()).isEqualTo(20.0);
    }

    @Test
    void forecastVelocity_excludesEmptySprintsWithinWindow() {
        List<Double> history = new ArrayList<>();
        history.add(20.0);
        history.add(null); // empty sprint, excluded
        history.add(22.0);

        VelocityForecast forecast = CapacityCalculator.forecastVelocity(history, 3);

        assertThat(forecast.sampleSize()).isEqualTo(2);
        assertThat(forecast.mean()).isEqualTo(21.0);
    }

    @Test
    void forecastVelocity_nonPositiveWindow_throws() {
        assertThatThrownBy(() -> CapacityCalculator.forecastVelocity(List.of(10.0), 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void firstSprintFallback_equalsCapaciteNetteTimesOneMinusMargin() {
        // capaciteNette = 8, margin = 0.15 -> 8 x 0.85 = 6.8, same as engagementRecommande above
        assertThat(CapacityCalculator.firstSprintFallback(8.0, 0.15)).isEqualTo(6.8);
    }

    @Test
    void firstSprintFallback_usedWhenForecastVelocityHasNoHistory() {
        CapacityEventInput event = baseEvent().members(baseMember().build()).build();
        EventCapacityResult sprintCapacity = CapacityCalculator.computeEventCapacity(event);

        assertThat(CapacityCalculator.forecastVelocity(List.of(), 3)).isNull();
        double fallback = CapacityCalculator.firstSprintFallback(sprintCapacity.totalCapaciteNette(), 0.15);

        assertThat(fallback).isEqualTo(sprintCapacity.totalEngagementRecommande());
    }

    // ── E11: round2 reproducibility ──────────────────────────────────────────────

    @Test
    void round2_isIdempotent() {
        double once = CapacityCalculator.round2(1.0 / 3);
        double twice = CapacityCalculator.round2(once);

        assertThat(twice).isEqualTo(once);
    }

    @Test
    void round2_roundsToTwoDecimals() {
        assertThat(CapacityCalculator.round2(3.14159)).isEqualTo(3.14);
        assertThat(CapacityCalculator.round2(2.0 / 3)).isEqualTo(0.67);
    }

    @Test
    void round2_sameInputAlwaysProducesSameOutput() {
        assertThat(CapacityCalculator.round2(7.0 / 9)).isEqualTo(CapacityCalculator.round2(7.0 / 9));
    }
}

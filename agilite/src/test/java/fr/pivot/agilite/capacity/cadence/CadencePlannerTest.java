package fr.pivot.agilite.capacity.cadence;

import fr.pivot.agilite.capacity.exception.CapacityValidationException;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link CadencePlanner} (F11.5 — PI/SAFe cadence) — pure date-math, no Spring
 * context.
 */
class CadencePlannerTest {

    private static final LocalDate PI_START = LocalDate.of(2026, 1, 5);
    private static final LocalDate PI_END = LocalDate.of(2026, 3, 29);

    // ── Basic tiling ────────────────────────────────────────────────────────────

    @Test
    void plan_withDayLength_tilesSequentialSprintsFromPiStart() {
        CadenceRequest request = new CadenceRequest(10, null, 3, false, null);

        List<SprintPlan> plans = CadencePlanner.plan(PI_START, PI_END, request);

        assertThat(plans).hasSize(3);
        assertThat(plans.get(0).name()).isEqualTo("Sprint 1");
        assertThat(plans.get(0).startDate()).isEqualTo(LocalDate.of(2026, 1, 5));
        assertThat(plans.get(0).endDate()).isEqualTo(LocalDate.of(2026, 1, 14));
        assertThat(plans.get(0).ipSprint()).isFalse();

        assertThat(plans.get(1).name()).isEqualTo("Sprint 2");
        assertThat(plans.get(1).startDate()).isEqualTo(LocalDate.of(2026, 1, 15));
        assertThat(plans.get(1).endDate()).isEqualTo(LocalDate.of(2026, 1, 24));

        assertThat(plans.get(2).name()).isEqualTo("Sprint 3");
        assertThat(plans.get(2).startDate()).isEqualTo(LocalDate.of(2026, 1, 25));
        assertThat(plans.get(2).endDate()).isEqualTo(LocalDate.of(2026, 2, 3));
    }

    @Test
    void plan_withWeekLength_convertsWeeksToDays() {
        CadenceRequest request = new CadenceRequest(null, 2, 2, false, null);

        List<SprintPlan> plans = CadencePlanner.plan(PI_START, PI_END, request);

        assertThat(plans).hasSize(2);
        assertThat(plans.get(0).startDate()).isEqualTo(LocalDate.of(2026, 1, 5));
        assertThat(plans.get(0).endDate()).isEqualTo(LocalDate.of(2026, 1, 18));
        assertThat(plans.get(1).startDate()).isEqualTo(LocalDate.of(2026, 1, 19));
        assertThat(plans.get(1).endDate()).isEqualTo(LocalDate.of(2026, 2, 1));
    }

    @Test
    void plan_withCustomNamePrefix_usesItForEachSprint() {
        CadenceRequest request = new CadenceRequest(7, null, 2, false, "PI26.1 Sprint");

        List<SprintPlan> plans = CadencePlanner.plan(PI_START, PI_END, request);

        assertThat(plans.get(0).name()).isEqualTo("PI26.1 Sprint 1");
        assertThat(plans.get(1).name()).isEqualTo("PI26.1 Sprint 2");
    }

    // ── SAFe IP sprint ──────────────────────────────────────────────────────────

    @Test
    void plan_withIpSprint_appendsTrailingSprintMarkedIp() {
        CadenceRequest request = new CadenceRequest(10, null, 2, true, null);

        List<SprintPlan> plans = CadencePlanner.plan(PI_START, PI_END, request);

        assertThat(plans).hasSize(3);
        SprintPlan ip = plans.get(2);
        assertThat(ip.ipSprint()).isTrue();
        assertThat(ip.name()).isEqualTo("Sprint 3 (IP)");
        assertThat(ip.startDate()).isEqualTo(LocalDate.of(2026, 1, 25));
        assertThat(ip.endDate()).isEqualTo(LocalDate.of(2026, 2, 3));
        assertThat(plans.get(0).ipSprint()).isFalse();
        assertThat(plans.get(1).ipSprint()).isFalse();
    }

    // ── Window-fit edge cases ──────────────────────────────────────────────────

    @Test
    void plan_exactlyFillingTheWindow_succeeds() {
        // PI window is 84 days (2026-01-05 .. 2026-03-29 inclusive); 6 x 14-day sprints = 84 days.
        CadenceRequest request = new CadenceRequest(14, null, 6, false, null);

        List<SprintPlan> plans = CadencePlanner.plan(PI_START, PI_END, request);

        assertThat(plans).hasSize(6);
        assertThat(plans.get(5).endDate()).isEqualTo(PI_END);
    }

    @Test
    void plan_oneDayOverTheWindow_throwsCadenceOverflow() {
        // 6 x 14-day sprints = 84 days = the whole window; one more day of IP sprint overflows it.
        CadenceRequest request = new CadenceRequest(14, null, 6, true, null);

        assertThatThrownBy(() -> CadencePlanner.plan(PI_START, PI_END, request))
                .isInstanceOf(CapacityValidationException.class)
                .satisfies(ex -> assertThat(((CapacityValidationException) ex).getCode())
                        .isEqualTo("CADENCE_OVERFLOW"));
    }

    @Test
    void plan_singleDayPiWithSingleDaySprint_succeeds() {
        CadenceRequest request = new CadenceRequest(1, null, 1, false, null);

        List<SprintPlan> plans = CadencePlanner.plan(PI_START, PI_START, request);

        assertThat(plans).hasSize(1);
        assertThat(plans.get(0).startDate()).isEqualTo(PI_START);
        assertThat(plans.get(0).endDate()).isEqualTo(PI_START);
    }

    // ── Cadence spec validation ─────────────────────────────────────────────────

    @Test
    void plan_neitherLengthSupplied_throwsInvalidCadence() {
        CadenceRequest request = new CadenceRequest(null, null, 2, false, null);

        assertThatThrownBy(() -> CadencePlanner.plan(PI_START, PI_END, request))
                .isInstanceOf(CapacityValidationException.class)
                .satisfies(ex -> assertThat(((CapacityValidationException) ex).getCode())
                        .isEqualTo("INVALID_CADENCE"));
    }

    @Test
    void plan_bothLengthsSupplied_throwsInvalidCadence() {
        CadenceRequest request = new CadenceRequest(10, 2, 2, false, null);

        assertThatThrownBy(() -> CadencePlanner.plan(PI_START, PI_END, request))
                .isInstanceOf(CapacityValidationException.class)
                .satisfies(ex -> assertThat(((CapacityValidationException) ex).getCode())
                        .isEqualTo("INVALID_CADENCE"));
    }

    @Test
    void plan_nonPositiveSprintLength_throwsInvalidCadence() {
        CadenceRequest request = new CadenceRequest(0, null, 2, false, null);

        assertThatThrownBy(() -> CadencePlanner.plan(PI_START, PI_END, request))
                .isInstanceOf(CapacityValidationException.class)
                .satisfies(ex -> assertThat(((CapacityValidationException) ex).getCode())
                        .isEqualTo("INVALID_CADENCE"));
    }
}

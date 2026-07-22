package fr.pivot.agilite.pi;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link PiIterationGenerator} — pure, no Spring context (US50.1.1).
 */
class PiIterationGeneratorTest {

    @Test
    void generate_defaultParams_producesFiveIterationsPlusIpSprint() {
        List<PiIterationGenerator.GeneratedIteration> result =
                PiIterationGenerator.generate(LocalDate.of(2026, 1, 5), 5, 2);

        assertThat(result).hasSize(6);
        assertThat(result.get(0).number()).isEqualTo(1);
        assertThat(result.get(0).label()).isEqualTo("IT1");
        assertThat(result.get(0).startDate()).isEqualTo(LocalDate.of(2026, 1, 5));
        assertThat(result.get(0).endDate()).isEqualTo(LocalDate.of(2026, 1, 18));

        assertThat(result.get(4).number()).isEqualTo(5);
        assertThat(result.get(4).label()).isEqualTo("IT5");

        assertThat(result.get(5).number()).isEqualTo(6);
        assertThat(result.get(5).label()).isEqualTo("IP Sprint");
    }

    @Test
    void generate_iterationsAreConsecutiveAndNonOverlapping() {
        List<PiIterationGenerator.GeneratedIteration> result =
                PiIterationGenerator.generate(LocalDate.of(2026, 3, 2), 3, 3);

        for (int i = 1; i < result.size(); i++) {
            LocalDate previousEnd = result.get(i - 1).endDate();
            LocalDate currentStart = result.get(i).startDate();
            assertThat(currentStart).isEqualTo(previousEnd.plusDays(1));
        }
    }

    @Test
    void generate_singleIteration_producesOneRegularIterationPlusIpSprint() {
        List<PiIterationGenerator.GeneratedIteration> result =
                PiIterationGenerator.generate(LocalDate.of(2026, 6, 1), 1, 1);

        assertThat(result).hasSize(2);
        assertThat(result.get(0).label()).isEqualTo("IT1");
        assertThat(result.get(1).label()).isEqualTo("IP Sprint");
        assertThat(result.get(1).number()).isEqualTo(2);
    }

    @Test
    void generate_maxBounds_producesTwelveIterationsPlusIpSprint() {
        List<PiIterationGenerator.GeneratedIteration> result =
                PiIterationGenerator.generate(LocalDate.of(2026, 1, 1), 12, 6);

        assertThat(result).hasSize(13);
        assertThat(result.get(11).label()).isEqualTo("IT12");
        assertThat(result.get(12).label()).isEqualTo("IP Sprint");
    }

    @Test
    void generate_ipSprintHasSameLengthAsRegularIterations() {
        List<PiIterationGenerator.GeneratedIteration> result =
                PiIterationGenerator.generate(LocalDate.of(2026, 1, 1), 2, 2);

        long regularLengthDays = ChronoUnit.DAYS.between(result.get(0).startDate(), result.get(0).endDate());
        long ipLengthDays = ChronoUnit.DAYS.between(result.getLast().startDate(), result.getLast().endDate());
        assertThat(ipLengthDays).isEqualTo(regularLengthDays);
    }
}

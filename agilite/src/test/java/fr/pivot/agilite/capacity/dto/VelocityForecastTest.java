package fr.pivot.agilite.capacity.dto;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests unitaires pour le record {@link VelocityForecast} (E11 — capacity planning).
 */
class VelocityForecastTest {

    @Test
    void accessors_shouldReturnConstructedValues() {
        final VelocityForecast forecast = new VelocityForecast(5, 40.0, 5.0, 0.125, 30.0, 50.0, false);

        assertThat(forecast.sampleSize()).isEqualTo(5);
        assertThat(forecast.mean()).isEqualTo(40.0);
        assertThat(forecast.stdDev()).isEqualTo(5.0);
        assertThat(forecast.coefficientOfVariation()).isEqualTo(0.125);
        assertThat(forecast.lowerBound()).isEqualTo(30.0);
        assertThat(forecast.upperBound()).isEqualTo(50.0);
        assertThat(forecast.widened()).isFalse();
    }

    @Test
    void accessors_shouldExposeWidened_whenCoefficientOfVariationHigh() {
        final VelocityForecast forecast = new VelocityForecast(5, 40.0, 15.0, 0.375, 20.0, 60.0, true);

        assertThat(forecast.widened()).isTrue();
    }
}

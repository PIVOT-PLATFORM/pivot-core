package fr.pivot.config;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.boot.micrometer.metrics.autoconfigure.MeterRegistryCustomizer;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests unitaires pour {@link MetricsConfig} — tags communs Micrometer {@code application} /
 * {@code instance} (EN04.3).
 */
class MetricsConfigTest {

    private final MetricsConfig config = new MetricsConfig();

    @Test
    void commonTagsCustomizer_shouldTagEveryMeter_withApplicationName() {
        final MeterRegistryCustomizer<MeterRegistry> customizer = config.commonTagsCustomizer("pivot-backend");
        final MeterRegistry registry = new SimpleMeterRegistry();

        customizer.customize(registry);
        registry.counter("some.metric").increment();

        final Counter counter = registry.get("some.metric").counter();
        assertThat(counter.getId().getTag("application")).isEqualTo("pivot-backend");
    }

    @Test
    void commonTagsCustomizer_shouldTagEveryMeter_withNonBlankInstance() {
        final MeterRegistryCustomizer<MeterRegistry> customizer = config.commonTagsCustomizer("pivot-backend");
        final MeterRegistry registry = new SimpleMeterRegistry();

        customizer.customize(registry);
        registry.counter("some.other.metric").increment();

        final Counter counter = registry.get("some.other.metric").counter();
        // Hostname resolution is environment-dependent (container id, CI runner name...) —
        // only the presence of a non-blank value is asserted, never a specific hostname.
        assertThat(counter.getId().getTag("instance")).isNotBlank();
    }

    @Test
    void commonTagsCustomizer_shouldApplySameTags_acrossDifferentMeterTypes() {
        final MeterRegistryCustomizer<MeterRegistry> customizer = config.commonTagsCustomizer("pivot-backend");
        final MeterRegistry registry = new SimpleMeterRegistry();

        customizer.customize(registry);
        registry.timer("some.timer").record(java.time.Duration.ofMillis(1));

        assertThat(registry.get("some.timer").timer().getId().getTag("application")).isEqualTo("pivot-backend");
    }
}

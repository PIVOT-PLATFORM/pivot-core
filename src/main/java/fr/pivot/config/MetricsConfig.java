package fr.pivot.config;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.micrometer.metrics.autoconfigure.MeterRegistryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.net.InetAddress;
import java.net.UnknownHostException;

/**
 * Common Micrometer tags applied to every metric this service exports (EN04.3).
 *
 * <p>{@code application} identifies the emitting service when a single Prometheus instance
 * scrapes multiple PIVOT backends (pivot-core plus, eventually, the module-core repos);
 * {@code instance} disambiguates replicas of the same service so a per-replica anomaly (e.g.
 * one container's JVM heap climbing) isn't silently averaged away with its siblings on a
 * dashboard or alert rule.
 */
@Configuration
public class MetricsConfig {

    /**
     * Registers {@code application} and {@code instance} as common tags on every meter
     * exported by the auto-configured {@link MeterRegistry} (JVM, HTTP, JDBC, custom).
     *
     * @param applicationName {@code spring.application.name} — identifies this service
     * @return the customizer Spring Boot applies to the {@link MeterRegistry} bean
     */
    @Bean
    public MeterRegistryCustomizer<MeterRegistry> commonTagsCustomizer(
            @Value("${spring.application.name}") final String applicationName) {
        final String instance = resolveInstanceId();
        return registry -> registry.config().commonTags("application", applicationName, "instance", instance);
    }

    /**
     * Resolves a stable identifier for this JVM instance — the container/host name.
     *
     * <p>Falls back to {@code "unknown"} instead of failing application startup: a missing
     * {@code instance} tag is a minor observability gap, never a reason to prevent the
     * service from coming up.
     *
     * @return the local hostname, or {@code "unknown"} if it cannot be resolved
     */
    private static String resolveInstanceId() {
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (final UnknownHostException ex) {
            return "unknown";
        }
    }
}

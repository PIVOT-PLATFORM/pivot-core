package fr.pivot.config;

import java.util.Arrays;
import java.util.List;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.MigrationInfoService;
import org.flywaydb.core.api.MigrationState;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

/**
 * EN04.4 — custom Flyway migration health indicator, feeding the {@code readiness} health
 * group (see {@code application.yml}, {@code management.endpoint.health.group.readiness}).
 *
 * <p>Spring Boot 4.1 ships no built-in Flyway health contributor — verified directly against
 * the {@code spring-boot-flyway}, {@code spring-boot-actuator-autoconfigure} and
 * {@code spring-boot-autoconfigure} jars on this project's classpath: none contain a health
 * package for Flyway. {@code FlywayEndpoint} (the {@code /actuator/flyway} endpoint, not
 * enabled here — see {@code management.endpoints.web.exposure.include}) only ever exposes
 * migration *info*, never a health signal. This bean fills that gap for the "Flyway migrations
 * OK" part of this Enabler's readiness AC.
 *
 * <p>Auto-detected as a {@link HealthIndicator} bean by
 * {@code HealthContributorRegistryAutoConfiguration} and exposed under the {@code flyway} key
 * in the health JSON — the bean name {@code flywayHealthIndicator} minus the conventional
 * {@code HealthIndicator} suffix (Spring Boot's default {@code HealthContributorNameGenerator}
 * behaviour, unchanged from Spring Boot 3.x despite the {@code org.springframework.boot.health}
 * package move — confirmed against the actual jar).
 *
 * <p>DOWN when any migration has definitively {@link MigrationState#FAILED}, or when any
 * migration known to Flyway (from its configured {@code classpath:db/migration} location) is
 * still {@link MigrationState#PENDING} — the schema is not (or no longer) fully migrated,
 * which is exactly what "Flyway migrations OK" in the readiness AC means. UP otherwise.
 */
@Component
public class FlywayHealthIndicator implements HealthIndicator {

    private final Flyway flyway;

    /**
     * Constructs the indicator.
     *
     * @param flyway the Flyway instance auto-configured against the application datasource
     */
    public FlywayHealthIndicator(final Flyway flyway) {
        this.flyway = flyway;
    }

    /**
     * Reports the Flyway migration health.
     *
     * @return {@code DOWN} with a detail count when migrations have failed or are pending,
     *         {@code UP} with the current schema version otherwise
     */
    @Override
    public Health health() {
        // Single flyway.info() call, reused below (all() + current()) — it re-validates
        // against the DB each time it is invoked (visible as a repeated "Database: jdbc:..."
        // log line otherwise), so calling it twice per health check is a needless extra
        // round-trip on a request that is already on the hot path for readiness polling.
        final MigrationInfoService info = flyway.info();
        final MigrationInfo[] all = info.all();

        final List<MigrationInfo> failed = Arrays.stream(all)
                .filter(mi -> mi.getState() == MigrationState.FAILED)
                .toList();
        if (!failed.isEmpty()) {
            return Health.down()
                    .withDetail("failedMigrations", failed.size())
                    .withDetail("firstFailed", failed.getFirst().getVersion())
                    .build();
        }

        final List<MigrationInfo> pending = Arrays.stream(all)
                .filter(mi -> mi.getState() == MigrationState.PENDING)
                .toList();
        if (!pending.isEmpty()) {
            return Health.down()
                    .withDetail("pendingMigrations", pending.size())
                    .withDetail("firstPending", pending.getFirst().getVersion())
                    .build();
        }

        final MigrationInfo current = info.current();
        return Health.up()
                .withDetail("schemaVersion", current == null ? "none" : current.getVersion().toString())
                .build();
    }
}

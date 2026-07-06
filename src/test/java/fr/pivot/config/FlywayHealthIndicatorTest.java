package fr.pivot.config;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.MigrationInfoService;
import org.flywaydb.core.api.MigrationState;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.Status;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link FlywayHealthIndicator} (EN04.4).
 *
 * <p>Covers the three {@link MigrationState} outcomes that matter for the readiness AC
 * ("Flyway migrations OK"): all applied (UP), a pending migration (DOWN), a failed migration
 * (DOWN, checked ahead of pending) — plus the degenerate no-migration-at-all case.
 */
@ExtendWith(MockitoExtension.class)
class FlywayHealthIndicatorTest {

    @Mock
    private Flyway flyway;

    @Mock
    private MigrationInfoService infoService;

    @Mock
    private MigrationInfo appliedMigration;

    @Mock
    private MigrationInfo pendingMigration;

    @Mock
    private MigrationInfo failedMigration;

    @Test
    void health_allMigrationsApplied_returnsUpWithSchemaVersion() {
        when(flyway.info()).thenReturn(infoService);
        when(infoService.all()).thenReturn(new MigrationInfo[] {appliedMigration});
        when(appliedMigration.getState()).thenReturn(MigrationState.SUCCESS);
        when(infoService.current()).thenReturn(appliedMigration);
        when(appliedMigration.getVersion()).thenReturn(MigrationVersion.fromVersion("1"));

        final Health health = new FlywayHealthIndicator(flyway).health();

        assertThat(health.getStatus()).isEqualTo(Status.UP);
        assertThat(health.getDetails()).containsEntry("schemaVersion", "1");
    }

    @Test
    void health_pendingMigration_returnsDownWithPendingCount() {
        when(flyway.info()).thenReturn(infoService);
        when(infoService.all()).thenReturn(new MigrationInfo[] {pendingMigration});
        when(pendingMigration.getState()).thenReturn(MigrationState.PENDING);
        when(pendingMigration.getVersion()).thenReturn(MigrationVersion.fromVersion("2"));

        final Health health = new FlywayHealthIndicator(flyway).health();

        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
        assertThat(health.getDetails()).containsEntry("pendingMigrations", 1);
    }

    @Test
    void health_failedMigration_returnsDownWithFailedCount_evenIfAlsoPendingElsewhere() {
        when(flyway.info()).thenReturn(infoService);
        when(infoService.all()).thenReturn(new MigrationInfo[] {failedMigration, pendingMigration});
        when(failedMigration.getState()).thenReturn(MigrationState.FAILED);
        when(failedMigration.getVersion()).thenReturn(MigrationVersion.fromVersion("3"));

        final Health health = new FlywayHealthIndicator(flyway).health();

        // Failed takes priority over pending — both are DOWN, but the failed detail is the
        // one surfaced, since a failed migration is the more actionable signal.
        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
        assertThat(health.getDetails()).containsEntry("failedMigrations", 1);
        assertThat(health.getDetails()).doesNotContainKey("pendingMigrations");
    }

    @Test
    void health_noMigrationsAtAll_returnsUpWithNoneSchemaVersion() {
        when(flyway.info()).thenReturn(infoService);
        when(infoService.all()).thenReturn(new MigrationInfo[0]);
        when(infoService.current()).thenReturn(null);

        final Health health = new FlywayHealthIndicator(flyway).health();

        assertThat(health.getStatus()).isEqualTo(Status.UP);
        assertThat(health.getDetails()).containsEntry("schemaVersion", "none");
    }
}

package fr.pivot.config;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.exception.FlywayValidateException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.flyway.autoconfigure.FlywayMigrationStrategy;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Testcontainers integration test for the shell (schema {@code public}) Flyway self-heal added by
 * {@link ModuleFlywayMigrationConfig#publicSchemaFlywayMigrationStrategy()}.
 *
 * <p>Recette went down on 2026-07-20 when an unrelated PR widened a {@code CHECK} constraint inside
 * the pre-BETA « fichier V1 unique » {@code public/V1__schema_init.sql}: the DB had already applied
 * an earlier snapshot of that V1, so the stored checksum drifted from the build's, Boot's plain
 * {@code migrate()} on its public-schema Flyway aborted with {@link FlywayValidateException} before
 * the {@code EntityManagerFactory} could build, and the whole app failed to start. The EN53
 * module-schema self-heal ({@code ModuleFlywaySelfHealIntegrationTest}) did not cover the shell
 * schema — Boot owns that migration, not {@code ModuleFlywayConfigurer}.
 *
 * <p>This test drives the <strong>real production strategy</strong> (the lambda returned by the
 * {@code @Bean} method above) against the <strong>real</strong> {@code classpath:db/migration/public}
 * migration, configuring Flyway exactly as Boot does for the public schema (see
 * {@code application.yml}: {@code baseline-on-migrate=true}, {@code baseline-version=0}). It proves
 * the strategy heals a drifted checksum where a plain {@code migrate()} aborts, and is an
 * inert no-op when history is already consistent.
 *
 * <p>{@link #recetteRepro_narrowConstraintAndDriftedChecksum_selfHealsAndWidensConstraint()} goes
 * further and reproduces the <strong>full</strong> recette incident, not just the checksum symptom:
 * it forces the schema back to the shape a database that only ever applied the OLD (narrow)
 * {@code V1__schema_init.sql} would have — narrow {@code chk_notifications_type}, drifted V1
 * checksum, no {@code V1.1} applied yet — then asserts the production strategy both (a) self-heals
 * the checksum drift (no {@link FlywayValidateException}) and (b) actually widens the constraint,
 * because {@code repair()} alone never re-runs V1's SQL (see
 * {@code V1_1__widen_notifications_type_check.sql}'s header). That second assertion is the one that
 * catches the gap {@code repair()}-then-{@code migrate()} alone leaves open: without the {@code V1.1}
 * forward migration, this test fails — the strategy boots cleanly (no exception) but a
 * {@code BOARD_SHARED} notification still cannot be inserted, exactly the silent runtime failure
 * recette would hit post-boot.
 */
@Testcontainers
class PublicSchemaFlywaySelfHealIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:18");

    private static final String SCHEMA = "public";

    /** The exact strategy Spring Boot invokes in production for the public-schema Flyway. */
    private final FlywayMigrationStrategy strategy =
            new ModuleFlywayMigrationConfig().publicSchemaFlywayMigrationStrategy();

    @Test
    @DisplayName("public V1 checksum drift: plain migrate fails, the production strategy heals it")
    void strategyHealsPublicSchemaChecksumDrift() throws SQLException {
        // Given: the public schema has been migrated once (V1 recorded with its real checksum),
        // then V1's stored checksum drifts — exactly what happens when the single mutable public
        // V1 is edited after a persistent DB (recette) already applied an earlier snapshot of it.
        publicFlyway().migrate();
        final int applied = storedChecksum("1");
        setStoredChecksum("1", applied + 1);

        // Then: a plain migrate() aborts with the exact recette failure mode (checksum mismatch)...
        assertThrows(
                FlywayValidateException.class,
                () -> publicFlyway().migrate(),
                "A drifted public V1 checksum must make Boot's plain migrate() fail validation");

        // ...but the production strategy (repair() before migrate()) self-heals the boot.
        assertDoesNotThrow(
                () -> strategy.migrate(publicFlyway()),
                "The public-schema strategy must recover from checksum drift");

        // And the stored checksum is realigned to the locally-resolved (build) migration.
        assertEquals(
                applied,
                storedChecksum("1"),
                "repair() must realign the stored public V1 checksum to the resolved value");
    }

    @Test
    @DisplayName("no drift: the production strategy is an inert no-op (repair changes nothing)")
    void strategyIsNoOpWhenHistoryConsistent() throws SQLException {
        // Given: a freshly migrated public schema with no drift at all.
        publicFlyway().migrate();
        final int applied = storedChecksum("1");

        // Then: invoking the strategy again neither throws nor mutates the consistent history.
        assertDoesNotThrow(
                () -> strategy.migrate(publicFlyway()),
                "The strategy must be a safe no-op on an already-consistent history");
        assertEquals(
                applied,
                storedChecksum("1"),
                "repair() on consistent history must leave the stored checksum untouched");
    }

    @Test
    @DisplayName(
            "recette repro: narrow constraint + drifted V1 checksum + no V1.1 yet — strategy heals "
                    + "the boot AND V1.1 widens the constraint (repair() alone would not)")
    void recetteRepro_narrowConstraintAndDriftedChecksum_selfHealsAndWidensConstraint()
            throws SQLException {
        // Given: a database that behaves exactly like recette before this fix — it ran V1 back
        // when chk_notifications_type only allowed the pre-US08.2.5 values, so (1) its notifications
        // table still enforces the OLD narrow list and (2) it has never seen V1.1 at all. We start
        // from a full migrate() (current build: wide V1 + V1.1) and roll the schema back to that
        // legacy shape, which is behaviourally equivalent to a DB that only ever ran the old V1.
        publicFlyway().migrate();
        final int resolvedV1Checksum = storedChecksum("1");
        narrowNotificationsTypeConstraintToLegacyValues();
        deleteHistoryRow("1.1");
        setStoredChecksum("1", resolvedV1Checksum + 1);

        // Sanity check: the narrow constraint really is in effect pre-fix (mirrors recette's
        // actual runtime failure mode once boot succeeds — a BOARD_SHARED insert violates the
        // legacy CHECK).
        assertThrows(
                SQLException.class,
                () -> insertNotification("BOARD_SHARED"),
                "Sanity check: the simulated legacy constraint must reject BOARD_SHARED");

        // Then: a plain migrate() aborts with the exact recette failure mode (checksum mismatch on
        // V1), same as the other test above — restated here because this test's starting state is
        // built differently (narrowed constraint, deleted V1.1 row) and must independently confirm
        // the drift is real before asserting the strategy heals it.
        assertThrows(
                FlywayValidateException.class,
                () -> publicFlyway().migrate(),
                "A drifted public V1 checksum must make Boot's plain migrate() fail validation");

        // When: the production strategy runs (repair() then migrate()) — this is the exact call
        // Boot's FlywayMigrationInitializer makes, before the EntityManagerFactory can build.
        assertDoesNotThrow(
                () -> strategy.migrate(publicFlyway()),
                "The public-schema strategy must recover from checksum drift and reach "
                        + "MIGRATE_DONE without throwing");

        // Then: V1's checksum is repaired to the resolved (current build) value...
        assertEquals(
                resolvedV1Checksum,
                storedChecksum("1"),
                "repair() must realign the stored public V1 checksum to the resolved value");

        // ...the widen migration (version 1.1) was actually applied (proves migrate() ran to
        // completion after repair(), not just that repair() itself didn't throw)...
        assertDoesNotThrow(
                () -> storedChecksum("1.1"),
                "V1.1 (widen) must have been applied by the self-heal migrate()");

        // ...and — the assertion that would fail without the V1.1 forward migration in this PR — the
        // constraint is now actually WIDE: both a legacy value and a new BOARD_* value insert
        // cleanly, proving repair()'s checksum realignment did not silently paper over the fact
        // that V1's SQL itself never re-ran on this "legacy" database.
        assertDoesNotThrow(
                () -> insertNotification("ROLE_CHANGED"),
                "Legacy notification types must keep working after the self-heal");
        assertDoesNotThrow(
                () -> insertNotification("BOARD_SHARED"),
                "BOARD_SHARED must be insertable after the self-heal widens the constraint via V1.1 "
                        + "— repair() alone (no V1.1) would leave this failing");
    }

    // ----------------------------------------------------------------
    // Helpers
    // ----------------------------------------------------------------

    /**
     * Builds a Flyway for the {@code public} schema against the real shell migration, configured
     * exactly as Spring Boot does in {@code application.yml} — {@code baseline-on-migrate=true},
     * {@code baseline-version=0}, {@code locations=classpath:db/migration/public}.
     *
     * @return the configured public-schema Flyway
     */
    private static Flyway publicFlyway() {
        return Flyway.configure()
                .dataSource(dataSource())
                .schemas(SCHEMA)
                .locations("classpath:db/migration/public")
                .baselineOnMigrate(true)
                .baselineVersion("0")
                .load();
    }

    private static DataSource dataSource() {
        return new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    private static Connection connection() throws SQLException {
        return DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    private static int storedChecksum(final String version) throws SQLException {
        try (Connection conn = connection();
                PreparedStatement ps = conn.prepareStatement(
                        "SELECT checksum FROM " + SCHEMA
                                + ".flyway_schema_history WHERE version = ?")) {
            ps.setString(1, version);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    throw new IllegalStateException("No history row for version " + version);
                }
                return rs.getInt("checksum");
            }
        }
    }

    private static void setStoredChecksum(final String version, final int checksum)
            throws SQLException {
        try (Connection conn = connection();
                PreparedStatement ps = conn.prepareStatement(
                        "UPDATE " + SCHEMA
                                + ".flyway_schema_history SET checksum = ? WHERE version = ?")) {
            ps.setInt(1, checksum);
            ps.setString(2, version);
            ps.executeUpdate();
        }
    }

    /** Deletes a {@code flyway_schema_history} row, simulating a version never applied there. */
    private static void deleteHistoryRow(final String version) throws SQLException {
        try (Connection conn = connection();
                PreparedStatement ps = conn.prepareStatement(
                        "DELETE FROM " + SCHEMA
                                + ".flyway_schema_history WHERE version = ?")) {
            ps.setString(1, version);
            ps.executeUpdate();
        }
    }

    /**
     * Rolls {@code notifications.chk_notifications_type} back to the OLD (pre-US08.2.5) value set,
     * simulating a database that only ever ran the narrow V1 that predates PR #236 — exactly
     * recette's state before this fix.
     */
    private static void narrowNotificationsTypeConstraintToLegacyValues() throws SQLException {
        try (Connection conn = connection();
                java.sql.Statement st = conn.createStatement()) {
            st.execute("ALTER TABLE notifications DROP CONSTRAINT chk_notifications_type");
            st.execute(
                    "ALTER TABLE notifications ADD CONSTRAINT chk_notifications_type CHECK ("
                            + "type IN ('ROLE_CHANGED', 'ACCOUNT_DEACTIVATED', 'SENSITIVE_ACTION', "
                            + "'UNKNOWN_DEVICE'))");
        }
    }

    /**
     * Inserts a minimal {@code notifications} row (with a throwaway tenant/user to satisfy the FKs)
     * of the given {@code type}, to exercise {@code chk_notifications_type} directly. Throws
     * {@link SQLException} (check violation) if {@code type} is not currently allowed.
     */
    private static void insertNotification(final String type) throws SQLException {
        try (Connection conn = connection()) {
            final long tenantId;
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO tenants (slug, name) VALUES (?, ?) RETURNING id")) {
                ps.setString(1, "notif-test-" + System.nanoTime());
                ps.setString(2, "Notif Test Tenant");
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    tenantId = rs.getLong(1);
                }
            }
            final long userId;
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO users (tenant_id, email) VALUES (?, ?) RETURNING id")) {
                ps.setLong(1, tenantId);
                ps.setString(2, "notif-test-" + System.nanoTime() + "@example.test");
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    userId = rs.getLong(1);
                }
            }
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO notifications (user_id, tenant_id, type, title, body) "
                            + "VALUES (?, ?, ?, 'test', 'test')")) {
                ps.setLong(1, userId);
                ps.setLong(2, tenantId);
                ps.setString(3, type);
                ps.executeUpdate();
            }
        }
    }
}

package fr.pivot.core.db;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import javax.sql.DataSource;
import org.flywaydb.core.api.exception.FlywayValidateException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Testcontainers integration test for the EN53 module-schema Flyway self-heal.
 *
 * <p>Reproduces, against a real PostgreSQL, the two ways a long-lived database (recette carried
 * its module schemas over from the modules' standalone-service era) diverges from a modulith
 * build under the pre-BETA « fichier V1 unique » convention, and asserts that the self-heal
 * (repair before migrate, plus {@code ignoreMigrationPatterns("*:missing")} in
 * {@link ModuleFlywayConfigurer}) recovers both — where a plain {@code migrate()} aborts the boot.
 *
 * <p>Each test targets its OWN schema (the shared static container is reused across tests) via a
 * schema-agnostic fixture migration ({@code classpath:db/selfheal_module}), so the tests never
 * interfere with each other or with {@link ModuleSchemaIsolationIntegrationTest}.
 */
@Testcontainers
class ModuleFlywaySelfHealIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:18");

    private static final String FIXTURE = "classpath:db/selfheal_module";

    @Test
    @DisplayName("checksum drift on an applied migration: plain migrate fails, repair+migrate heals")
    void repairHealsChecksumDrift() throws SQLException {
        final String schema = "selfheal_drift";
        final var configurer = new ModuleFlywayConfigurer(schema, FIXTURE);

        // Given: the module schema has been migrated once (V1 recorded with its real checksum),
        // then V1's stored checksum drifts — exactly what happens when the single mutable V1 is
        // edited after a persistent DB already applied an earlier snapshot of it.
        configurer.createFlyway(dataSource()).migrate();
        final int applied = storedChecksum(schema, "1");
        final int drifted = applied + 1;
        setStoredChecksum(schema, "1", drifted);

        // Then: a plain migrate() aborts with the recette failure mode (checksum mismatch)...
        assertThrows(
                FlywayValidateException.class,
                () -> configurer.createFlyway(dataSource()).migrate(),
                "A drifted checksum must make plain migrate() fail validation");

        // ...but repair() before migrate() self-heals: it realigns the stored checksum and the
        // subsequent migrate() runs clean.
        assertDoesNotThrow(
                () -> {
                    final var flyway = configurer.createFlyway(dataSource());
                    flyway.repair();
                    flyway.migrate();
                },
                "repair() before migrate() must recover from checksum drift");

        // And the stored checksum is back in sync with the resolved (local) migration.
        assertEquals(
                applied,
                storedChecksum(schema, "1"),
                "repair() must realign the stored checksum to the locally-resolved value");
    }

    @Test
    @DisplayName("applied migration with no local script (feature re-homed) is tolerated by migrate")
    void migrateToleratesAppliedButMissingMigration() throws SQLException {
        final String schema = "selfheal_missing";
        final var configurer = new ModuleFlywayConfigurer(schema, FIXTURE);

        // Given: the schema is migrated, then a later version is recorded as applied although no
        // script for it exists in this build — the situation left behind when a migration is
        // dropped / a feature re-homed to another schema during the modulith absorption.
        configurer.createFlyway(dataSource()).migrate();
        insertAppliedMigrationWithoutScript(schema, "999", "phantom re-homed feature");

        // Then: migrate() tolerates the "applied but not resolved locally" entry (thanks to
        // ignoreMigrationPatterns("*:missing")) instead of aborting the boot.
        assertDoesNotThrow(
                () -> configurer.createFlyway(dataSource()).migrate(),
                "An applied-but-missing migration must not fail the module migration");
    }

    // ----------------------------------------------------------------
    // Helpers
    // ----------------------------------------------------------------

    private DataSource dataSource() {
        return new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    private static Connection connection() throws SQLException {
        return DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    private static int storedChecksum(final String schema, final String version)
            throws SQLException {
        try (Connection conn = connection();
                PreparedStatement ps = conn.prepareStatement(
                        "SELECT checksum FROM " + schema
                                + ".flyway_schema_history WHERE version = ?")) {
            ps.setString(1, version);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    throw new IllegalStateException(
                            "No history row for version " + version + " in schema " + schema);
                }
                return rs.getInt("checksum");
            }
        }
    }

    private static void setStoredChecksum(
            final String schema, final String version, final int checksum) throws SQLException {
        try (Connection conn = connection();
                PreparedStatement ps = conn.prepareStatement(
                        "UPDATE " + schema
                                + ".flyway_schema_history SET checksum = ? WHERE version = ?")) {
            ps.setInt(1, checksum);
            ps.setString(2, version);
            ps.executeUpdate();
        }
    }

    private static void insertAppliedMigrationWithoutScript(
            final String schema, final String version, final String description)
            throws SQLException {
        try (Connection conn = connection();
                PreparedStatement ps = conn.prepareStatement(
                        "INSERT INTO " + schema + ".flyway_schema_history "
                                + "(installed_rank, version, description, type, script, checksum, "
                                + "installed_by, execution_time, success) "
                                + "SELECT COALESCE(MAX(installed_rank), 0) + 1, ?, ?, 'SQL', ?, 0, "
                                + "'test', 1, true FROM " + schema + ".flyway_schema_history")) {
            ps.setString(1, version);
            ps.setString(2, description);
            ps.setString(3, "V" + version + "__" + description.replace(' ', '_') + ".sql");
            ps.executeUpdate();
        }
    }
}

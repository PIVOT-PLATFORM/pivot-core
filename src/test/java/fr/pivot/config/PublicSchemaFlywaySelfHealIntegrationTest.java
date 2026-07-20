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
}

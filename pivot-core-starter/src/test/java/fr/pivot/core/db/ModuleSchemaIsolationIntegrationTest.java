package fr.pivot.core.db;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Testcontainers integration test validating the EN17.4 schema isolation convention.
 *
 * <p>Verifies that {@link ModuleFlywayConfigurer}:
 * <ol>
 *   <li>Creates the declared module schema ({@code test_module}) when migrations run.</li>
 *   <li>Runs module migrations inside the module schema only — the {@code public} schema
 *       is not touched (no spurious tables created there by the module migration).</li>
 *   <li>Module DML operations succeed in the module schema and cannot reach the public schema.</li>
 * </ol>
 *
 * <p>This test deliberately does NOT boot a Spring ApplicationContext — it exercises
 * {@link ModuleFlywayConfigurer#createFlyway} via Flyway's programmatic API to keep the
 * test fast and dependency-free.
 */
@Testcontainers
class ModuleSchemaIsolationIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:18");

    @Test
    @DisplayName("module schema is created and migrations run in test_module, not in public")
    void moduleSchemaIsIsolatedFromPublic() throws SQLException {
        // Given: a ModuleFlywayConfigurer targeting "test_module" schema
        final var configurer = new ModuleFlywayConfigurer("test_module", "classpath:db/test_module");

        // When: Flyway runs via the factory
        configurer.createFlyway(dataSource()).migrate();

        // Then: the test_module schema and its table exist
        try (Connection conn = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())) {

            assertTrue(schemaExists(conn, "test_module"),
                    "Schema 'test_module' must be created by ModuleFlywayConfigurer");

            assertTrue(tableExistsInSchema(conn, "test_module", "items"),
                    "Table 'test_module.items' must be created by the module migration");

            // Isolation guard: the public schema must NOT contain a table named 'items'
            assertFalse(tableExistsInSchema(conn, "public", "items"),
                    "Module migration must NOT create tables in the public schema");
        }
    }

    @Test
    @DisplayName("module DML succeeds in module schema; public schema stays clean")
    void moduleSchemaDoesNotPollutPublicSchema() throws SQLException {
        // Ensure schema + table exist for this test (container is shared but tests are independent)
        new ModuleFlywayConfigurer("test_module", "classpath:db/test_module")
                .createFlyway(dataSource()).migrate();

        try (Connection conn = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())) {

            // Insert into module schema table — unconditional: fails loudly if schema setup failed
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO test_module.items (label) VALUES (?)")) {
                ps.setString(1, "isolation-test-item");
                ps.executeUpdate();
            }

            // The public schema must NOT contain a table named 'items'
            assertFalse(tableExistsInSchema(conn, "public", "items"),
                    "The public schema must remain clean — no module table must leak there");
        }
    }

    // ----------------------------------------------------------------
    // Helpers
    // ----------------------------------------------------------------

    private DataSource dataSource() {
        return new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    private static boolean schemaExists(final Connection conn, final String schemaName)
            throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT 1 FROM information_schema.schemata WHERE schema_name = ?")) {
            ps.setString(1, schemaName);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    private static boolean tableExistsInSchema(
            final Connection conn, final String schemaName, final String tableName)
            throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT 1 FROM information_schema.tables "
                        + "WHERE table_schema = ? AND table_name = ?")) {
            ps.setString(1, schemaName);
            ps.setString(2, tableName);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }
}

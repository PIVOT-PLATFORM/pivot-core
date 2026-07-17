package fr.pivot.core.db;

import org.flywaydb.core.Flyway;

import javax.sql.DataSource;

/**
 * Factory that creates a dedicated {@link Flyway} instance for a single module schema,
 * completely independent of Spring Boot's auto-configured Flyway.
 *
 * <p>Each {@code pivot-xxx-core} module repo declares one {@code @Bean Flyway} that delegates
 * to this factory. Because every module gets its own {@code Flyway} instance, schemas and
 * migration locations never interfere with each other — the last-writer-wins problem that
 * a shared {@code FlywayConfigurationCustomizer} would introduce is avoided by design.
 *
 * <h2>Usage in a module repo</h2>
 * <pre>{@code
 * // pivot-agilite-core — AgiliteFlywayConfig.java
 * @Configuration
 * public class AgiliteFlywayConfig {
 *
 *     @Bean
 *     @DependsOn("entityManagerFactory")
 *     public Flyway agiliteFlywayMigrator(DataSource dataSource) {
 *         return new ModuleFlywayConfigurer("agilite", "classpath:db/agilite")
 *                 .createFlyway(dataSource);
 *     }
 * }
 * }</pre>
 *
 * <h2>Schema convention (EN17.4)</h2>
 * <ul>
 *   <li>Each module manages its own PostgreSQL schema via a dedicated Flyway instance.</li>
 *   <li>Cross-schema foreign keys are allowed <strong>only</strong> toward
 *       {@code public.teams(id)} and {@code public.tenants(id)}.</li>
 *   <li>Module schemas must <strong>never</strong> write to the {@code public} schema.</li>
 * </ul>
 *
 * @param schema         PostgreSQL schema name for this module (e.g. {@code "agilite"}).
 * @param migrationsPath Flyway location of migration scripts for this module
 *                       (e.g. {@code "classpath:db/agilite"}).
 */
public record ModuleFlywayConfigurer(String schema, String migrationsPath) {

    /** Validates that schema and migrationsPath are non-blank. */
    public ModuleFlywayConfigurer {
        if (schema == null || schema.isBlank()) {
            throw new IllegalArgumentException("ModuleFlywayConfigurer: schema must not be blank");
        }
        if (migrationsPath == null || migrationsPath.isBlank()) {
            throw new IllegalArgumentException(
                    "ModuleFlywayConfigurer: migrationsPath must not be blank");
        }
    }

    /**
     * Builds a dedicated {@link Flyway} instance configured for this module schema.
     *
     * @param dataSource the shared application {@link DataSource}
     * @return a configured (but not yet migrated) {@link Flyway} instance
     */
    public Flyway createFlyway(final DataSource dataSource) {
        if (dataSource == null) {
            throw new IllegalArgumentException("ModuleFlywayConfigurer: dataSource must not be null");
        }
        return Flyway.configure()
                .dataSource(dataSource)
                .schemas(schema)
                .defaultSchema(schema)
                .locations(migrationsPath)
                .createSchemas(true)
                .load();
    }
}

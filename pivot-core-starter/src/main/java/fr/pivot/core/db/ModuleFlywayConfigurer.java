package fr.pivot.core.db;

import org.flywaydb.core.api.configuration.FluentConfiguration;
import org.springframework.boot.flyway.autoconfigure.FlywayConfigurationCustomizer;

/**
 * Configurer allowing a pivot-xxx-core module repo to register its own Flyway migrations
 * in a dedicated PostgreSQL schema, without touching pivot-core's {@code public} schema.
 *
 * <h2>Usage in a module repo</h2>
 * <pre>{@code
 * // pivot-pilotage-core — PilotageFlywayConfig.java
 * @Configuration
 * public class PilotageFlywayConfig {
 *
 *     @Bean
 *     public ModuleFlywayConfigurer pilotageFlywayConfigurer() {
 *         return new ModuleFlywayConfigurer("pilotage", "classpath:db/pilotage");
 *     }
 * }
 * }</pre>
 *
 * <h2>Schema convention (EN17.4)</h2>
 * <ul>
 *   <li>Each module manages its own PostgreSQL schema via Flyway.</li>
 *   <li>Cross-schema foreign keys are allowed <strong>only</strong> toward
 *       {@code public.teams(id)} and {@code public.tenants(id)}.</li>
 *   <li>Module schemas must <strong>never</strong> write to the {@code public} schema.</li>
 * </ul>
 *
 * <h2>Migration naming</h2>
 * Migrations must follow the pattern {@code V{n}__{description}.sql} inside
 * {@code migrationsPath}. The schema is created automatically by Flyway if
 * {@code createSchemas} is enabled (default: {@code true}).
 *
 * @param schema         PostgreSQL schema name for this module (e.g. {@code "pilotage"}).
 *                       Must be a valid SQL identifier.
 * @param migrationsPath Flyway location of migration scripts for this module
 *                       (e.g. {@code "classpath:db/pilotage"} or
 *                       {@code "filesystem:/opt/migrations/pilotage"}).
 */
public record ModuleFlywayConfigurer(String schema, String migrationsPath)
        implements FlywayConfigurationCustomizer {

    /**
     * Constructs a module Flyway configurer, validating that {@code schema} and
     * {@code migrationsPath} are non-blank.
     *
     * @param schema         target PostgreSQL schema — must be a non-blank SQL identifier
     * @param migrationsPath Flyway location — must be non-blank
     * @throws IllegalArgumentException if either argument is blank
     */
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
     * Applies the module schema and migrations path to the Flyway configuration.
     *
     * <p>Sets:
     * <ul>
     *   <li>{@code schemas} — the target schema (Flyway creates it if absent)</li>
     *   <li>{@code defaultSchema} — same, so that unqualified table names resolve to it</li>
     *   <li>{@code locations} — the migration scripts path for this module</li>
     *   <li>{@code createSchemas} — {@code true} (idempotent: {@code CREATE SCHEMA IF NOT EXISTS})</li>
     * </ul>
     *
     * @param configuration the Flyway configuration to customise
     */
    @Override
    public void customize(final FluentConfiguration configuration) {
        configuration
                .schemas(schema)
                .defaultSchema(schema)
                .locations(migrationsPath)
                .createSchemas(true);
    }
}

package fr.pivot.core.db;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Unit tests for {@link ModuleFlywayConfigurer}.
 *
 * <p>Validates construction guards and that the record accessor methods expose the
 * correct values. The Flyway integration behaviour (schema creation, migration execution)
 * is validated separately in {@link ModuleSchemaIsolationIntegrationTest}.
 */
class ModuleFlywayConfigurerTest {

    @Test
    @DisplayName("record accessors expose the constructed values")
    void accessorsExposeValues() {
        final ModuleFlywayConfigurer configurer =
                new ModuleFlywayConfigurer("pilotage", "classpath:db/pilotage");

        assertEquals("pilotage", configurer.schema());
        assertEquals("classpath:db/pilotage", configurer.migrationsPath());
    }

    @Test
    @DisplayName("blank schema throws IllegalArgumentException")
    void blankSchemaThrows() {
        assertThrows(IllegalArgumentException.class,
                () -> new ModuleFlywayConfigurer("", "classpath:db/pilotage"));
    }

    @Test
    @DisplayName("null schema throws IllegalArgumentException")
    void nullSchemaThrows() {
        assertThrows(IllegalArgumentException.class,
                () -> new ModuleFlywayConfigurer(null, "classpath:db/pilotage"));
    }

    @Test
    @DisplayName("blank migrationsPath throws IllegalArgumentException")
    void blankMigrationsPathThrows() {
        assertThrows(IllegalArgumentException.class,
                () -> new ModuleFlywayConfigurer("pilotage", ""));
    }

    @Test
    @DisplayName("null migrationsPath throws IllegalArgumentException")
    void nullMigrationsPathThrows() {
        assertThrows(IllegalArgumentException.class,
                () -> new ModuleFlywayConfigurer("pilotage", null));
    }

    @Test
    @DisplayName("whitespace-only schema throws IllegalArgumentException")
    void whitespaceSchemaThrows() {
        assertThrows(IllegalArgumentException.class,
                () -> new ModuleFlywayConfigurer("   ", "classpath:db/pilotage"));
    }
}

package fr.pivot.core.autoconfigure;

import fr.pivot.core.db.ModuleFlywayConfigurer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link PivotCoreAutoConfiguration}.
 *
 * <p>Exercises the registration logger bean — the only stateful component
 * in the auto-configuration — without booting a full Spring context.
 */
class PivotCoreAutoConfigurationTest {

    private final PivotCoreAutoConfiguration.ModuleFlywayConfiguration config =
            new PivotCoreAutoConfiguration.ModuleFlywayConfiguration();

    @Test
    @DisplayName("registration logger bean created with zero configurers")
    void moduleFlywayRegistrationLogger_noConfigurers_returnsSentinel() {
        @SuppressWarnings("unchecked")
        final ObjectProvider<ModuleFlywayConfigurer> empty = mock(ObjectProvider.class);
        when(empty.orderedStream()).thenReturn(Stream.empty());

        final var logger = config.moduleFlywayRegistrationLogger(empty);

        assertNotNull(logger, "Sentinel bean must never be null");
        verify(empty).orderedStream();
    }

    @Test
    @DisplayName("registration logger logs each registered configurer")
    void moduleFlywayRegistrationLogger_withConfigurer_logsAndReturns() {
        final var configurer = new ModuleFlywayConfigurer("pilotage", "classpath:db/pilotage");
        @SuppressWarnings("unchecked")
        final ObjectProvider<ModuleFlywayConfigurer> provider = mock(ObjectProvider.class);
        when(provider.orderedStream()).thenReturn(Stream.of(configurer));

        final var logger = config.moduleFlywayRegistrationLogger(provider);

        assertNotNull(logger, "Sentinel bean must never be null");
        verify(provider).orderedStream();
    }
}

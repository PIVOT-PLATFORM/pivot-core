package fr.pivot.config;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.info.BuildProperties;
import org.springframework.core.env.Environment;
import org.slf4j.LoggerFactory;

import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link StartupLogListener} (EN04.1 — startup log: version, port, active profile).
 */
@ExtendWith(MockitoExtension.class)
class StartupLogListenerTest {

    @Mock private Environment environment;
    @Mock private ObjectProvider<BuildProperties> buildPropertiesProvider;

    @Test
    void logStartup_logsVersionPortAndProfile_whenBuildPropertiesAvailable() {
        when(buildPropertiesProvider.getIfAvailable()).thenReturn(buildProperties("1.2.3"));
        when(environment.getProperty(eq("server.port"), anyString())).thenAnswer(inv -> inv.getArgument(1));
        when(environment.getProperty(eq("local.server.port"), anyString())).thenReturn("8080");
        when(environment.getActiveProfiles()).thenReturn(new String[] {"dev"});

        final String message = logStartupAndCapture();

        assertThat(message).contains("event=APPLICATION_STARTED");
        assertThat(message).contains("version=1.2.3");
        assertThat(message).contains("port=8080");
        assertThat(message).contains("profile=dev");
    }

    @Test
    void logStartup_logsUnknownVersion_whenBuildPropertiesAbsent() {
        when(buildPropertiesProvider.getIfAvailable()).thenReturn(null);
        when(environment.getProperty(eq("server.port"), anyString())).thenAnswer(inv -> inv.getArgument(1));
        when(environment.getProperty(eq("local.server.port"), anyString())).thenAnswer(inv -> inv.getArgument(1));
        when(environment.getActiveProfiles()).thenReturn(new String[0]);

        final String message = logStartupAndCapture();

        assertThat(message).contains("version=unknown");
    }

    @Test
    void logStartup_logsDefaultProfile_whenNoActiveProfiles() {
        when(buildPropertiesProvider.getIfAvailable()).thenReturn(null);
        when(environment.getProperty(eq("server.port"), anyString())).thenAnswer(inv -> inv.getArgument(1));
        when(environment.getProperty(eq("local.server.port"), anyString())).thenAnswer(inv -> inv.getArgument(1));
        when(environment.getActiveProfiles()).thenReturn(new String[0]);

        final String message = logStartupAndCapture();

        assertThat(message).contains("profile=default");
    }

    @Test
    void logStartup_joinsMultipleActiveProfiles() {
        when(buildPropertiesProvider.getIfAvailable()).thenReturn(null);
        when(environment.getProperty(eq("server.port"), anyString())).thenAnswer(inv -> inv.getArgument(1));
        when(environment.getProperty(eq("local.server.port"), anyString())).thenAnswer(inv -> inv.getArgument(1));
        when(environment.getActiveProfiles()).thenReturn(new String[] {"dev", "extra"});

        final String message = logStartupAndCapture();

        assertThat(message).contains("profile=dev,extra");
    }

    @Test
    void logStartup_fallsBackToServerPort_whenLocalServerPortAbsent() {
        when(buildPropertiesProvider.getIfAvailable()).thenReturn(null);
        when(environment.getProperty(eq("server.port"), anyString())).thenReturn("9090");
        when(environment.getProperty(eq("local.server.port"), anyString())).thenAnswer(inv -> inv.getArgument(1));
        when(environment.getActiveProfiles()).thenReturn(new String[0]);

        final String message = logStartupAndCapture();

        assertThat(message).contains("port=9090");
    }

    @Test
    void logStartup_fallsBackToUnknownPort_whenNeitherPortPropertyAvailable() {
        when(buildPropertiesProvider.getIfAvailable()).thenReturn(null);
        when(environment.getProperty(eq("server.port"), anyString())).thenAnswer(inv -> inv.getArgument(1));
        when(environment.getProperty(eq("local.server.port"), anyString())).thenAnswer(inv -> inv.getArgument(1));
        when(environment.getActiveProfiles()).thenReturn(new String[0]);

        final String message = logStartupAndCapture();

        assertThat(message).contains("port=unknown");
    }

    // ----------------------------------------------------------------
    // Helpers
    // ----------------------------------------------------------------

    private String logStartupAndCapture() {
        final StartupLogListener listener = new StartupLogListener(environment, buildPropertiesProvider);
        final Logger logger = (Logger) LoggerFactory.getLogger(StartupLogListener.class);
        final ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            listener.logStartup();
        } finally {
            logger.detachAppender(appender);
        }
        assertThat(appender.list).hasSize(1);
        return appender.list.get(0).getFormattedMessage();
    }

    private static BuildProperties buildProperties(final String version) {
        final Properties props = new Properties();
        props.setProperty("version", version);
        props.setProperty("group", "fr.pivot");
        props.setProperty("artifact", "pivot-core");
        return new BuildProperties(props);
    }
}

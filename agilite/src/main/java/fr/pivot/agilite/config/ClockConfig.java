package fr.pivot.agilite.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/**
 * Provides the shared {@link Clock} bean (US09.1.1) — Spring Boot does not register one by
 * default. Using an injected {@link Clock} rather than {@code Instant.now()} directly lets
 * {@code PokerRoomServiceTest} assert exact {@code expiresAt} values with a fixed clock.
 *
 * <p>{@link ConditionalOnMissingBean} (US47.1.1) — the {@code collaboratif} module gained its
 * own equivalent {@code fr.pivot.collaboratif.config.ClockConfig}; both module JARs are combined
 * on one classpath in the deployed {@code app} module, so without this guard whichever of the
 * two gets processed second would collide with a bean already named {@code clock}. Whichever
 * runs first here wins; harmless either way since both produce {@link Clock#systemUTC()}.
 */
@Configuration
public class ClockConfig {

    /**
     * The system UTC clock used everywhere a room timestamp is computed.
     *
     * @return {@link Clock#systemUTC()}
     */
    @Bean
    @ConditionalOnMissingBean(Clock.class)
    public Clock clock() {
        return Clock.systemUTC();
    }
}

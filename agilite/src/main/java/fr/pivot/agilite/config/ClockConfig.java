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
 *
 * <p>The {@link Configuration @Configuration} annotation is given an explicit bean name
 * ({@code agiliteClockConfig}) because {@code fr.pivot.collaboratif.config.ClockConfig} shares
 * the same simple class name — with the default name (derived from the simple class name),
 * Spring registers both configuration classes themselves under the same bean name
 * ({@code clockConfig}) on that combined classpath and fails to start with a
 * {@code ConflictingBeanDefinitionException}, independently of the {@link Clock} bean conflict
 * {@link ConditionalOnMissingBean} guards against.
 */
@Configuration("agiliteClockConfig")
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

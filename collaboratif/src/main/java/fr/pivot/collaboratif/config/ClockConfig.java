package fr.pivot.collaboratif.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/**
 * Provides the shared {@link Clock} bean for the {@code collaboratif} module (US47.1.1) —
 * Spring Boot does not register one by default, and this module has its own {@code config}
 * package rather than depending on {@code fr.pivot.agilite.config.ClockConfig} (the {@code
 * agilite} module's own equivalent copy — no cross-module dependency by design).
 *
 * <p>{@link ConditionalOnMissingBean} matters here specifically because the two module JARs are
 * combined on one classpath in the deployed {@code app} module (both {@code pivot-agilite} and
 * {@code pivot-collaboratif} are its dependencies) — without it, both this class and agilite's
 * would each unconditionally register a bean literally named {@code clock}, and Spring Boot
 * would refuse to start (bean definition conflict). In each module's own isolated test/build
 * classpath (the other module's classes aren't present), this one applies normally.
 */
@Configuration
public class ClockConfig {

    /**
     * The system UTC clock used everywhere a collaboratif timestamp is computed.
     *
     * @return {@link Clock#systemUTC()}
     */
    @Bean
    @ConditionalOnMissingBean(Clock.class)
    public Clock clock() {
        return Clock.systemUTC();
    }
}

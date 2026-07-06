package fr.pivot.config;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.springframework.boot.actuate.info.Info;
import org.springframework.boot.actuate.info.InfoContributor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * EN04.2 — Spring Actuator {@code /actuator/info} contributor for the active Spring profile.
 *
 * <p>App version ({@code build.*}, via the {@code spring-boot-maven-plugin} {@code build-info}
 * execution) and git commit SHA ({@code git.*}, via {@code git-commit-id-maven-plugin}) are
 * surfaced automatically by Spring Boot's built-in {@code BuildPropertiesInfoContributor} and
 * {@code GitInfoContributor} once their respective generated files
 * ({@code META-INF/build-info.properties}, {@code git.properties}) are on the classpath — no
 * custom code needed for those two. The active Spring profile has no built-in contributor, so
 * this bean adds one explicitly.
 */
@Configuration
public class ActuatorConfig {

    /**
     * Exposes the currently active Spring profile(s) under {@code info.profile.active} in the
     * {@code /actuator/info} response. Falls back to {@code default} (Spring's own default
     * profile name) when no profile is explicitly activated, mirroring
     * {@link Environment#getActiveProfiles()} semantics rather than returning an empty list.
     *
     * @param environment the Spring environment, queried for active profiles
     * @return an {@link InfoContributor} adding the active profile(s) to {@code /actuator/info}
     */
    @Bean
    public InfoContributor activeProfileInfoContributor(final Environment environment) {
        return (final Info.Builder builder) -> {
            final String[] active = environment.getActiveProfiles();
            final List<String> profiles = active.length == 0
                ? List.of(environment.getDefaultProfiles())
                : Arrays.asList(active);
            builder.withDetail("profile", Map.of("active", profiles));
        };
    }
}

package fr.pivot.core.autoconfigure;

import fr.pivot.core.db.ModuleFlywayConfigurer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Root auto-configuration for {@code fr.pivot:pivot-core-starter}.
 *
 * <p>Activated via
 * {@code META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports}.
 * Consuming module repos ({@code pivot-xxx-core}) add {@code fr.pivot:pivot-core-starter} as a
 * dependency; this class configures the shared Spring context automatically.
 *
 * <h2>Exported packages</h2>
 * <ul>
 *   <li>{@code fr.pivot.core.auth} — Spring Security config, opaque token filter, OIDC RS</li>
 *   <li>{@code fr.pivot.core.tenant} — {@code TenantContext}, {@code TenantContextHolder},
 *       {@code @TenantAware}</li>
 *   <li>{@code fr.pivot.core.team} — {@code Team}, {@code TeamMember} (public schema entities)</li>
 *   <li>{@code fr.pivot.core.modules} — {@code PivotModule} interface, registry,
 *       {@code @RequiresModule}</li>
 *   <li>{@code fr.pivot.core.db} — Flyway baseline public schema, multi-schema DataSource config,
 *       {@link ModuleFlywayConfigurer}</li>
 * </ul>
 */
@AutoConfiguration
public class PivotCoreAutoConfiguration {

    private static final Logger LOG = LoggerFactory.getLogger(PivotCoreAutoConfiguration.class);

    /**
     * Inner configuration that registers module-level Flyway configurers.
     *
     * <p>Each {@code pivot-xxx-core} module repo declares a {@link ModuleFlywayConfigurer} bean
     * (see its Javadoc for usage). This configuration logs each registered configurer at startup
     * so operators can verify that all module schemas are discovered.
     *
     * <p>Conditional on {@code FlywayConfigurationCustomizer} being on the classpath —
     * i.e. {@code spring-boot-flyway} is present. This keeps the starter functional even when
     * a consuming project opts out of Flyway (test slice, embedded DB, etc.).
     */
    @Configuration
    @ConditionalOnClass(name = "org.springframework.boot.flyway.autoconfigure.FlywayConfigurationCustomizer")
    static class ModuleFlywayConfiguration {

        /**
         * Logs all {@link ModuleFlywayConfigurer} beans registered in the Spring context.
         *
         * <p>Actual Flyway customisation happens through Spring Boot's auto-configuration:
         * every {@code FlywayConfigurationCustomizer} bean is picked up automatically.
         * This bean purely provides observability (startup log) so operators can verify
         * that all expected module schemas are registered.
         *
         * @param configurers all {@link ModuleFlywayConfigurer} beans in the context
         * @return a no-op sentinel bean confirming registration
         */
        @Bean
        public ModuleFlywayRegistrationLogger moduleFlywayRegistrationLogger(
                final ObjectProvider<ModuleFlywayConfigurer> configurers) {
            configurers.orderedStream().forEach(c ->
                    LOG.info("event=MODULE_FLYWAY_CONFIGURER_REGISTERED schema={} migrationsPath={}",
                            c.schema(), c.migrationsPath()));
            return new ModuleFlywayRegistrationLogger();
        }
    }

    /**
     * Sentinel record confirming that module Flyway configurers have been registered.
     *
     * <p>Acts as a Spring bean to materialise the registration step in the context graph,
     * making it inspectable and testable.
     */
    public record ModuleFlywayRegistrationLogger() {
    }
}

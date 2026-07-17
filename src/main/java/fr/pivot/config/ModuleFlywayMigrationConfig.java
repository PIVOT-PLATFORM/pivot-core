package fr.pivot.config;

import fr.pivot.core.db.ModuleFlywayConfigurer;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.DependsOn;

/**
 * EN53.1 — wiring that lets the {@code public} (shell) schema and the {@code agilite} module
 * schema coexist as separate {@link Flyway} instances in the same Spring context, without
 * breaking Spring Boot's own Flyway auto-configuration.
 *
 * <h2>Why this lives here and not in {@code agilite/}</h2>
 *
 * <p>{@code agilite}'s own handoff ({@code agilite/src/main/resources/application.yml} header
 * comment, EN53.1 Vague 1) explicitly removes {@code spring.flyway.*} from its own config and
 * states the app integrator must ensure its {@code classpath:db/migration/agilite} migrations
 * (schema {@code agilite}, {@code baseline-on-migrate: true}, {@code baseline-version: 0}) run —
 * it does not declare its own {@link Flyway} bean (verified: no {@code
 * fr.pivot.agilite.config.*FlywayConfig} class, no {@code ModuleFlywayConfigurer} usage anywhere
 * under {@code agilite/src/main/java}). This class is that integration point, using {@code
 * pivot-core-starter}'s established per-module convention ({@code
 * fr.pivot.core.db.ModuleFlywayConfigurer} — see its Javadoc there): {@code
 * new ModuleFlywayConfigurer("agilite", "classpath:db/migration/agilite").createFlyway(dataSource)}.
 * Placed under the shell's {@code fr.pivot.config} (the one shell package this task's file scope
 * allows touching) rather than inside {@code agilite}'s own config package purely because of that
 * scope constraint — functionally this couples the shell to a specific module's schema/migration
 * path, which should arguably move into {@code agilite}'s own module once ownership boundaries
 * allow it (follow-up, not done here).
 *
 * <h2>Problem</h2>
 *
 * <p>Before EN53.1, this application had exactly one {@link Flyway} bean: the one Spring Boot
 * auto-configures from {@code spring.flyway.*} (bean name {@code "flyway"}, migrating the
 * {@code public} schema — {@code classpath:db/migration/public}, see {@code application.yml}
 * for why that location was renamed from plain {@code db/migration}). Adding a second, module
 * {@link Flyway} bean for {@code agilite} raises two concrete problems:
 * <ol>
 *   <li>Spring Boot's own {@code FlywayAutoConfiguration.flywayInitializer(Flyway flyway, ...)}
 *       {@code @Bean} method takes a plain, unqualified {@code Flyway} parameter. With two
 *       candidates and neither marked {@code @Primary}, constructor autowiring is ambiguous.
 *       (In practice Spring's by-name fallback would likely still resolve it correctly, since
 *       the parameter is literally named {@code flyway} and Boot's own bean is named {@code
 *       "flyway"} — but that fallback is implicit and depends on {@code -parameters} compiler
 *       output; this class makes the resolution explicit and robust instead of relying on it.)</li>
 *   <li>Nothing is otherwise wired to call {@code .migrate()} on the module's {@code Flyway}
 *       bean: {@code ModuleFlywayConfigurer.createFlyway(...)} only builds and loads the
 *       configuration, it never migrates (see its Javadoc/source) — Boot's own {@code
 *       FlywayMigrationInitializer} only ever drives the one {@code Flyway} bean it is wired to.</li>
 * </ol>
 *
 * <h2>Mechanism</h2>
 *
 * <ol>
 *   <li>{@link #agiliteFlywayMigrator(DataSource)} declares the {@code agilite} schema's
 *       {@link Flyway} instance (schema {@code agilite}, {@code classpath:db/migration/agilite},
 *       matching the fixed EN53.1 migration contract). {@code @DependsOn("entityManagerFactory")}
 *       matches the ordering shown in {@code ModuleFlywayConfigurer}'s own Javadoc usage example.</li>
 *   <li>{@link #shellFlywayPrimaryBeanFactoryPostProcessor()} marks Spring Boot's own {@code
 *       "flyway"} bean definition {@code @Primary} <em>after</em> bean definitions are
 *       registered but <em>before</em> any bean is instantiated (the standard, minimal-risk
 *       technique for retrofitting {@code @Primary} onto a bean this class does not itself
 *       define — see the {@code static} modifier requirement below). This keeps Spring Boot's
 *       own {@code flywayInitializer} bean deterministically wired to the shell/public schema,
 *       preserving its existing behaviour and timing (runs during context refresh, exactly as
 *       before EN53.1) with zero change to any other class, including {@code
 *       FlywayHealthIndicator} (its unqualified {@code Flyway} constructor parameter now
 *       resolves unambiguously to the {@code @Primary} bean).</li>
 *   <li>{@link #moduleSchemaFlywayMigrationRunner(ObjectProvider)} explicitly migrates
 *       <strong>every</strong> {@link Flyway} bean present in the context, via an {@link
 *       ApplicationRunner} (runs once, after the context has fully refreshed — i.e. strictly
 *       after Boot's own {@code FlywayMigrationInitializer} already migrated the shell schema
 *       as an {@code InitializingBean} during refresh). Re-migrating the already-migrated shell
 *       bean is a safe, idempotent no-op (Flyway skips schemas with no pending migrations); the
 *       new effect is that {@link #agiliteFlywayMigrator(DataSource)} — and any future module
 *       bean registered the same way — gets migrated too, generically, without this runner
 *       needing to know individual module bean names. This deliberately avoids declaring a
 *       competing {@code FlywayMigrationInitializer}-typed bean: Boot's own is {@code
 *       @ConditionalOnMissingBean} on that exact type (unqualified), so a second bean of that
 *       type would silently disable Boot's own initializer for the shell schema — a regression
 *       this design avoids by using a different bean type ({@code ApplicationRunner}) for the
 *       module migration trigger.</li>
 * </ol>
 *
 * <h2>Known risk — verify in CI</h2>
 *
 * <p>Not independently verified by running the application (no local JDK 24 — see CLAUDE.md JDK
 * gap): the two-Flyway-bean disambiguation, the {@code agilite.retro_formats}/etc. tables
 * actually appearing after startup, and the exact Spring Modulith version resolving (see parent
 * POM) all need confirmation via a real CI build/Testcontainers boot, not just structural review.
 * {@code ModularityTests} covers the module-boundary aspect only, not this runtime wiring.
 */
@Configuration
public class ModuleFlywayMigrationConfig {

    private static final Logger LOG = LoggerFactory.getLogger(ModuleFlywayMigrationConfig.class);

    /**
     * Bean name Spring Boot's {@code FlywayAutoConfiguration} registers its auto-configured
     * {@link Flyway} bean under (method name {@code flyway()} in {@code
     * org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration}, Spring Boot 4.1) —
     * the shell/public schema instance.
     */
    private static final String SHELL_FLYWAY_BEAN_NAME = "flyway";

    /**
     * Dedicated {@link Flyway} instance for the {@code agilite} module schema — see class
     * Javadoc. Independent of Spring Boot's auto-configured {@code flyway} bean: its own
     * {@code agilite.flyway_schema_history} table (schema-qualified, {@link
     * ModuleFlywayConfigurer#createFlyway} sets {@code schemas}/{@code defaultSchema} to {@code
     * agilite}), its own migration location, no interaction with the shell's schema.
     *
     * @param dataSource the shared application {@link DataSource}
     * @return a configured (not yet migrated — see {@link
     *     #moduleSchemaFlywayMigrationRunner(ObjectProvider)}) {@link Flyway} instance
     */
    @Bean
    @DependsOn("entityManagerFactory")
    public Flyway agiliteFlywayMigrator(final DataSource dataSource) {
        return new ModuleFlywayConfigurer("agilite", "classpath:db/migration/agilite")
                .createFlyway(dataSource);
    }

    /**
     * Marks the shell's auto-configured {@code flyway} bean definition {@code @Primary}.
     *
     * <p>Declared as a {@code static} {@code @Bean} method returning a {@link
     * BeanFactoryPostProcessor} — the documented Spring pattern for mutating another bean's
     * definition before instantiation without forcing this configuration class itself to be
     * instantiated early (which a non-static {@code @Bean BeanFactoryPostProcessor} method
     * would do, logging a Spring warning and risking other {@code @Bean} definitions in this
     * same class not yet being registered/available).
     *
     * @return a post-processor that flags the shell Flyway bean definition as primary, if present
     */
    @Bean
    static BeanFactoryPostProcessor shellFlywayPrimaryBeanFactoryPostProcessor() {
        return beanFactory -> {
            if (beanFactory.containsBeanDefinition(SHELL_FLYWAY_BEAN_NAME)) {
                beanFactory.getBeanDefinition(SHELL_FLYWAY_BEAN_NAME).setPrimary(true);
                LOG.info("event=SHELL_FLYWAY_MARKED_PRIMARY name={}", SHELL_FLYWAY_BEAN_NAME);
            } else {
                LOG.warn("event=SHELL_FLYWAY_BEAN_NOT_FOUND name={} — Spring Boot's Flyway "
                        + "auto-configuration bean name may have changed (or spring.flyway.enabled "
                        + "is false); multi-schema Flyway disambiguation in "
                        + "ModuleFlywayMigrationConfig will not behave as designed — verify in CI",
                        SHELL_FLYWAY_BEAN_NAME);
            }
        };
    }

    /**
     * Migrates every {@link Flyway} bean present in the context after the application context
     * has fully started (shell + {@link #agiliteFlywayMigrator(DataSource)}, and any future
     * module bean registered the same way).
     *
     * <p>Runs after context refresh — i.e. after Boot's own {@code FlywayMigrationInitializer}
     * has already migrated the shell/public schema bean during refresh (see class Javadoc).
     * Migrating that bean again here is a safe no-op. For {@link
     * #agiliteFlywayMigrator(DataSource)}, this is what actually triggers its migration —
     * nothing else in the application does.
     *
     * @param flywayBeans every {@link Flyway} bean registered in the context (shell + modules)
     * @return the runner performing the migration pass
     */
    @Bean
    public ApplicationRunner moduleSchemaFlywayMigrationRunner(final ObjectProvider<Flyway> flywayBeans) {
        return args -> flywayBeans.orderedStream().forEach(flyway -> {
            LOG.info("event=MODULE_SCHEMA_FLYWAY_MIGRATE_START");
            flyway.migrate();
        });
    }
}

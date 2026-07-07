package fr.pivot.core;

import fr.pivot.core.autoconfigure.PivotCoreAutoConfiguration;
import fr.pivot.core.db.ModuleFlywayConfigurer;
import fr.pivot.core.modules.ModuleRegistry;
import fr.pivot.core.modules.autoconfigure.PivotModulesAutoConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Consumption smoke test for {@code fr.pivot:pivot-core-starter} (EN17.1).
 *
 * <p>Boots every {@code @AutoConfiguration} class exported by the starter in a single
 * {@link ApplicationContextRunner}, simulating what a consuming {@code pivot-xxx-core}
 * module repo gets for free by adding the starter as a Maven dependency — closest
 * approximation achievable within this repo of EN17.1's "repo test qui importe
 * pivot-core-starter et démarre sans erreur" criterion; a genuine external-repo
 * consumption test additionally requires the artifact to be published (post-merge/
 * post-release — see {@code pivot-core#171}), not reproducible pre-merge.
 */
class PivotCoreStarterAutoConfigurationIntegrationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    PivotCoreAutoConfiguration.class,
                    PivotModulesAutoConfiguration.class));

    @Test
    void starterBootsCleanly_withBothAutoConfigurationsCombined() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(ModuleRegistry.class);
            assertThat(context).hasSingleBean(PivotCoreAutoConfiguration.ModuleFlywayRegistrationLogger.class);
        });
    }

    @Test
    void starterBootsCleanly_withAModuleFlywayConfigurerRegistered() {
        runner.withBean(ModuleFlywayConfigurer.class,
                        () -> new ModuleFlywayConfigurer("pilotage", "classpath:db/pilotage"))
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(PivotCoreAutoConfiguration.ModuleFlywayRegistrationLogger.class);
                });
    }
}

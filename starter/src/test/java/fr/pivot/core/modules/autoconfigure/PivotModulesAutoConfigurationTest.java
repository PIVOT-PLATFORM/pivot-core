package fr.pivot.core.modules.autoconfigure;

import fr.pivot.core.modules.ModuleRegistry;
import fr.pivot.core.modules.PivotModule;
import fr.pivot.core.modules.cache.ModuleActivationCacheService;
import fr.pivot.core.tenant.TenantContext;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests unitaires pour {@link PivotModulesAutoConfiguration}.
 *
 * <p>Traçabilité EN03.1 — critère « un repo module externe peut enregistrer son
 * implémentation PivotModule via {@code @Bean} Spring sans modifier pivot-core » :
 * {@link ExternalModuleConfig} simule la configuration d'un repo {@code pivot-xxx-core}.
 */
class PivotModulesAutoConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(PivotModulesAutoConfiguration.class));

    @Test
    void shouldCreateEmptyRegistry_whenNoModuleBeans() {
        runner.run(context -> {
            assertThat(context).hasSingleBean(ModuleRegistry.class);
            assertThat(context.getBean(ModuleRegistry.class).count()).isZero();
        });
    }

    @Test
    void shouldRegisterExternalModuleBean_withoutModifyingPivotCore() {
        runner.withUserConfiguration(ExternalModuleConfig.class).run(context -> {
            final ModuleRegistry registry = context.getBean(ModuleRegistry.class);

            assertThat(registry.count()).isEqualTo(1);
            assertThat(registry.isRegistered("external-module")).isTrue();
            assertThat(registry.findById("external-module"))
                    .hasValueSatisfying(module -> assertThat(module.getName()).isEqualTo("Module externe"));
        });
    }

    @Test
    void shouldBackOff_whenApplicationProvidesItsOwnRegistry() {
        runner.withUserConfiguration(CustomRegistryConfig.class).run(context -> {
            assertThat(context).hasSingleBean(ModuleRegistry.class);
            assertThat(context.getBean(ModuleRegistry.class))
                    .isSameAs(context.getBean(CustomRegistryConfig.class).customRegistry());
        });
    }

    /**
     * Traçabilité : résolution du bug {@code moduleCount=0} — un module métier PIVOT tourne
     * comme service Spring Boot séparé et ne peut jamais s'enregistrer comme bean {@link
     * PivotModule} dans le contexte de pivot-core ; seul le catalogue statique {@code
     * pivot.modules.catalog} peut le faire apparaître dans le registre.
     */
    @Test
    void shouldRegisterModuleFromStaticCatalog_withoutAnyDiscoveredBean() {
        runner.withUserConfiguration(ModuleActivationCacheServiceStubConfig.class)
                .withPropertyValues(
                        "pivot.modules.catalog[0].id=whiteboard",
                        "pivot.modules.catalog[0].name=Tableau blanc collaboratif",
                        "pivot.modules.catalog[0].version=0.1.0",
                        "pivot.modules.catalog[0].description=Tableau blanc collaboratif temps réel")
                .run(context -> {
                    final ModuleRegistry registry = context.getBean(ModuleRegistry.class);

                    assertThat(registry.count()).isEqualTo(1);
                    assertThat(registry.isRegistered("whiteboard")).isTrue();
                    assertThat(registry.findById("whiteboard"))
                            .hasValueSatisfying(module -> {
                                assertThat(module.getName()).isEqualTo("Tableau blanc collaboratif");
                                assertThat(module.getVersion()).isEqualTo("0.1.0");
                                assertThat(module.getDescription())
                                        .isEqualTo("Tableau blanc collaboratif temps réel");
                            });
                });
    }

    /**
     * Given un module découvert par bean ET un module catalogué (ids distincts),
     * when le registre se construit,
     * then les deux sources sont fusionnées sans collision.
     */
    @Test
    void shouldMergeDiscoveredBeansAndStaticCatalog() {
        runner.withUserConfiguration(ExternalModuleConfig.class, ModuleActivationCacheServiceStubConfig.class)
                .withPropertyValues(
                        "pivot.modules.catalog[0].id=whiteboard",
                        "pivot.modules.catalog[0].name=Tableau blanc collaboratif",
                        "pivot.modules.catalog[0].version=0.1.0")
                .run(context -> {
                    final ModuleRegistry registry = context.getBean(ModuleRegistry.class);

                    assertThat(registry.count()).isEqualTo(2);
                    assertThat(registry.isRegistered("external-module")).isTrue();
                    assertThat(registry.isRegistered("whiteboard")).isTrue();
                });
    }

    /**
     * Fournit un {@link ModuleActivationCacheService} minimal (mock Mockito) pour les scénarios
     * catalogue statique — cette classe n'est jamais réellement invoquée dans ces tests
     * (aucun appel à {@code isEnabled}), seule sa présence comme bean est nécessaire pour que
     * l'injection {@code @Lazy} du bean {@code moduleRegistry} se résolve.
     */
    @Configuration(proxyBeanMethods = false)
    static class ModuleActivationCacheServiceStubConfig {

        @Bean
        ModuleActivationCacheService moduleActivationCacheService() {
            return org.mockito.Mockito.mock(ModuleActivationCacheService.class);
        }
    }

    /**
     * Simule un repo module externe ({@code pivot-xxx-core}) déclarant son module
     * par simple {@code @Bean} — aucune modification de pivot-core requise.
     */
    @Configuration(proxyBeanMethods = false)
    static class ExternalModuleConfig {

        @Bean
        PivotModule externalModule() {
            return new PivotModule() {
                @Override
                public String getId() {
                    return "external-module";
                }

                @Override
                public String getName() {
                    return "Module externe";
                }

                @Override
                public String getVersion() {
                    return "1.0.0";
                }

                @Override
                public String getDescription() {
                    return "Module de test";
                }

                @Override
                public boolean isEnabled(final TenantContext ctx) {
                    return true;
                }
            };
        }
    }

    /**
     * Application fournissant son propre registre — l'auto-configuration doit se retirer.
     */
    @Configuration(proxyBeanMethods = true)
    static class CustomRegistryConfig {

        @Bean
        ModuleRegistry customRegistry() {
            return new ModuleRegistry(List.of());
        }
    }
}

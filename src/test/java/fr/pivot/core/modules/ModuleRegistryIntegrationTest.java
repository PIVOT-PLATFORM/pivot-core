package fr.pivot.core.modules;

import fr.pivot.AbstractIntegrationTest;
import fr.pivot.core.modules.event.ModuleActivatedEvent;
import fr.pivot.core.modules.event.ModuleDeactivatedEvent;
import fr.pivot.core.tenant.TenantContext;
import fr.pivot.tenant.repository.TenantRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests d'intégration du système de modules (PostgreSQL via Testcontainers).
 *
 * <p>Traçabilité EN03.1 :
 * <ul>
 *   <li>auto-découverte d'un bean {@link PivotModule} déclaré par {@code @Bean}
 *       (simulation d'un repo module externe) → {@link ModuleRegistry} ;</li>
 *   <li>migration Flyway {@code V1__schema_init.sql} : persistance, contrainte
 *       unique (tenant_id, module_id), FK vers {@code public.tenants} ;</li>
 *   <li>bus d'événements {@code ApplicationEventPublisher} : événements typés publiés
 *       sur activation / désactivation.</li>
 * </ul>
 */
@Import(ModuleRegistryIntegrationTest.TestModuleConfig.class)
@RecordApplicationEvents
class ModuleRegistryIntegrationTest extends AbstractIntegrationTest {

    private static final String MODULE_ID = "it-test-module";

    @Autowired
    private ModuleRegistry moduleRegistry;

    @Autowired
    private ModuleActivationService activationService;

    @Autowired
    private ModuleActivationRepository repository;

    @Autowired
    private TenantRepository tenantRepository;

    private Long tenantId;

    @BeforeEach
    void setUp() {
        tenantId = tenantRepository.findBySlug("pivot-saas").orElseThrow().getId();
    }

    @AfterEach
    void tearDown() {
        repository.deleteAll();
    }

    // ----------------------------------------------------------------
    // Auto-découverte du registre
    // ----------------------------------------------------------------

    @Test
    void registry_shouldDiscoverModuleBeanDeclaredByExternalConfiguration() {
        assertThat(moduleRegistry.isRegistered(MODULE_ID)).isTrue();
        assertThat(moduleRegistry.findById(MODULE_ID))
                .hasValueSatisfying(module -> {
                    assertThat(module.getName()).isEqualTo("Module de test TI");
                    assertThat(module.getVersion()).isEqualTo("1.0.0");
                });
        assertThat(moduleRegistry.getModules()).isNotEmpty();
    }

    // ----------------------------------------------------------------
    // Activation : persistance + événements
    // ----------------------------------------------------------------

    @Test
    void activate_shouldPersistRowAndPublishActivatedEvent(final ApplicationEvents events) {
        activationService.activate(tenantId, MODULE_ID);

        final ModuleActivation saved =
                repository.findByTenantIdAndModuleId(tenantId, MODULE_ID).orElseThrow();
        assertThat(saved.isEnabled()).isTrue();
        assertThat(saved.getUpdatedAt()).isNotNull();

        assertThat(events.stream(ModuleActivatedEvent.class))
                .singleElement()
                .satisfies(event -> {
                    assertThat(event.tenantId()).isEqualTo(tenantId);
                    assertThat(event.moduleId()).isEqualTo(MODULE_ID);
                });
    }

    @Test
    void deactivate_shouldUpdateRowAndPublishDeactivatedEvent(final ApplicationEvents events) {
        activationService.activate(tenantId, MODULE_ID);
        activationService.deactivate(tenantId, MODULE_ID);

        final ModuleActivation saved =
                repository.findByTenantIdAndModuleId(tenantId, MODULE_ID).orElseThrow();
        assertThat(saved.isEnabled()).isFalse();

        assertThat(events.stream(ModuleDeactivatedEvent.class))
                .singleElement()
                .satisfies(event -> {
                    assertThat(event.tenantId()).isEqualTo(tenantId);
                    assertThat(event.moduleId()).isEqualTo(MODULE_ID);
                });
    }

    @Test
    void activate_shouldBeIdempotent_noDuplicateEvent(final ApplicationEvents events) {
        activationService.activate(tenantId, MODULE_ID);
        activationService.activate(tenantId, MODULE_ID);

        assertThat(repository.findAllByTenantId(tenantId)).hasSize(1);
        assertThat(events.stream(ModuleActivatedEvent.class)).hasSize(1);
    }

    @Test
    void isEnabled_shouldDefaultToFalse_whenNoRow() {
        assertThat(activationService.isEnabled(tenantId, MODULE_ID)).isFalse();
    }

    // ----------------------------------------------------------------
    // Error case : module inconnu
    // ----------------------------------------------------------------

    @Test
    void activate_shouldRejectUnknownModule_withoutPersistingAnything() {
        assertThatThrownBy(() -> activationService.activate(tenantId, "ghost-module"))
                .isInstanceOf(UnknownModuleException.class);

        assertThat(repository.findAllByTenantId(tenantId)).isEmpty();
    }

    // ----------------------------------------------------------------
    // Security / intégrité : contraintes BDD (migration V3)
    // ----------------------------------------------------------------

    @Test
    void schema_shouldEnforceUniqueTenantModulePair() {
        final ModuleActivation first = new ModuleActivation(tenantId, MODULE_ID);
        repository.saveAndFlush(first);

        final ModuleActivation duplicate = new ModuleActivation(tenantId, MODULE_ID);

        assertThatThrownBy(() -> repository.saveAndFlush(duplicate))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void schema_shouldEnforceForeignKeyToTenants() {
        final ModuleActivation orphan = new ModuleActivation(999_999L, MODULE_ID);

        assertThatThrownBy(() -> repository.saveAndFlush(orphan))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    /**
     * Simule un repo module externe : déclaration d'un {@link PivotModule} par
     * {@code @Bean} — le registre le découvre sans modification de pivot-core.
     */
    @TestConfiguration(proxyBeanMethods = false)
    static class TestModuleConfig {

        @Bean
        PivotModule integrationTestModule() {
            return new PivotModule() {
                @Override
                public String getId() {
                    return MODULE_ID;
                }

                @Override
                public String getName() {
                    return "Module de test TI";
                }

                @Override
                public String getVersion() {
                    return "1.0.0";
                }

                @Override
                public boolean isEnabled(final TenantContext ctx) {
                    return true;
                }
            };
        }
    }
}

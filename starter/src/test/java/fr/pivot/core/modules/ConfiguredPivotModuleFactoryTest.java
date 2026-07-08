package fr.pivot.core.modules;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests unitaires pour {@link ConfiguredPivotModuleFactory}.
 */
@ExtendWith(MockitoExtension.class)
class ConfiguredPivotModuleFactoryTest {

    @Mock
    private ModuleActivationService moduleActivationService;

    /**
     * Given un catalogue vide,
     * when fromCatalog() est appelé,
     * then la liste résultante est vide (pas d'exception, pas de module fantôme).
     */
    @Test
    void fromCatalog_shouldReturnEmptyList_whenCatalogEmpty() {
        final ModuleCatalogProperties properties = new ModuleCatalogProperties(List.of());

        final List<PivotModule> modules = ConfiguredPivotModuleFactory.fromCatalog(properties, moduleActivationService);

        assertThat(modules).isEmpty();
    }

    /**
     * Given un catalogue avec plusieurs entrées,
     * when fromCatalog() est appelé,
     * then un PivotModule est construit par entrée, dans l'ordre déclaré, partageant la même
     * instance de ModuleActivationService.
     */
    @Test
    void fromCatalog_shouldBuildOneModulePerEntry_inDeclaredOrder() {
        final ModuleCatalogProperties properties = new ModuleCatalogProperties(List.of(
                new ModuleCatalogProperties.CatalogEntry("whiteboard", "Tableau blanc collaboratif", "0.1.0"),
                new ModuleCatalogProperties.CatalogEntry("roadmap", "Roadmap", "0.1.0")));

        final List<PivotModule> modules = ConfiguredPivotModuleFactory.fromCatalog(properties, moduleActivationService);

        assertThat(modules).hasSize(2);
        assertThat(modules.get(0).getId()).isEqualTo("whiteboard");
        assertThat(modules.get(1).getId()).isEqualTo("roadmap");
    }
}

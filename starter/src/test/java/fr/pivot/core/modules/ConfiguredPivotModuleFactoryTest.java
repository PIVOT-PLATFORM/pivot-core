package fr.pivot.core.modules;

import fr.pivot.core.modules.cache.ModuleActivationCacheService;
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
    private ModuleActivationCacheService moduleActivationCacheService;

    /**
     * Given un catalogue vide,
     * when fromCatalog() est appelé,
     * then la liste résultante est vide (pas d'exception, pas de module fantôme).
     */
    @Test
    void fromCatalog_shouldReturnEmptyList_whenCatalogEmpty() {
        final ModuleCatalogProperties properties = new ModuleCatalogProperties(List.of());

        final List<PivotModule> modules = ConfiguredPivotModuleFactory.fromCatalog(properties, moduleActivationCacheService);

        assertThat(modules).isEmpty();
    }

    /**
     * Given un catalogue avec plusieurs entrées,
     * when fromCatalog() est appelé,
     * then un PivotModule est construit par entrée, dans l'ordre déclaré, partageant la même
     * instance de ModuleActivationCacheService.
     */
    @Test
    void fromCatalog_shouldBuildOneModulePerEntry_inDeclaredOrder() {
        final ModuleCatalogProperties properties = new ModuleCatalogProperties(List.of(
                new ModuleCatalogProperties.CatalogEntry(
                        "whiteboard", "Tableau blanc collaboratif", "0.1.0",
                        "Tableau blanc collaboratif temps réel"),
                new ModuleCatalogProperties.CatalogEntry("roadmap", "Roadmap", "0.1.0", "Roadmap produit")));

        final List<PivotModule> modules = ConfiguredPivotModuleFactory.fromCatalog(properties, moduleActivationCacheService);

        assertThat(modules).hasSize(2);
        assertThat(modules.get(0).getId()).isEqualTo("whiteboard");
        assertThat(modules.get(0).getDescription()).isEqualTo("Tableau blanc collaboratif temps réel");
        assertThat(modules.get(1).getId()).isEqualTo("roadmap");
        assertThat(modules.get(1).getDescription()).isEqualTo("Roadmap produit");
    }

    /**
     * Given une entrée de catalogue sans description (liaison de configuration incomplète),
     * when fromCatalog() est appelé,
     * then le module construit expose une description vide plutôt que {@code null} —
     * normalisation portée par {@link ModuleCatalogProperties.CatalogEntry}.
     */
    @Test
    void fromCatalog_shouldNormalizeNullDescriptionToEmptyString() {
        final ModuleCatalogProperties properties = new ModuleCatalogProperties(List.of(
                new ModuleCatalogProperties.CatalogEntry("roadmap", "Roadmap", "0.1.0", null)));

        final List<PivotModule> modules = ConfiguredPivotModuleFactory.fromCatalog(properties, moduleActivationCacheService);

        assertThat(modules).hasSize(1);
        assertThat(modules.get(0).getDescription()).isEmpty();
    }
}

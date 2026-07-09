package fr.pivot.core.modules.autoconfigure;

import fr.pivot.core.modules.ConfiguredPivotModuleFactory;
import fr.pivot.core.modules.ModuleActivationService;
import fr.pivot.core.modules.ModuleCatalogProperties;
import fr.pivot.core.modules.ModuleRegistry;
import fr.pivot.core.modules.PivotModule;
import fr.pivot.core.modules.cache.ModuleActivationCacheService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Lazy;

import java.util.ArrayList;
import java.util.List;

/**
 * Auto-configuration du système de modules PIVOT — exportée par
 * {@code fr.pivot:pivot-core-starter} via
 * {@code META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports}.
 *
 * <p>Le {@link ModuleRegistry} combine deux sources de modules :
 * <ul>
 *   <li>auto-découverte de beans {@link PivotModule} dans le contexte Spring — utile si un
 *       module tourne un jour dans le même process que pivot-core (tests, composition
 *       avancée) ;</li>
 *   <li>{@link ModuleCatalogProperties} ({@code pivot.modules.catalog}) — la source réelle en
 *       pratique : un module métier PIVOT ({@code pivot-xxx-core}) tourne comme service Spring
 *       Boot <b>séparé</b> et ne peut donc jamais s'enregistrer comme bean dans le contexte de
 *       pivot-core lui-même. Sans cette seconde source, {@link ModuleRegistry} reste vide en
 *       toutes circonstances réelles (confirmé en local : {@code moduleCount=0} alors qu'un
 *       module métier tourne et sert du trafic réel sur son propre port).</li>
 * </ul>
 */
@AutoConfiguration
@EnableConfigurationProperties(ModuleCatalogProperties.class)
public class PivotModulesAutoConfiguration {

    private static final Logger LOG = LoggerFactory.getLogger(PivotModulesAutoConfiguration.class);

    /**
     * Crée le registre des modules à partir des beans {@link PivotModule} découverts et du
     * catalogue statique {@link ModuleCatalogProperties}.
     *
     * <p>{@code @ConditionalOnMissingBean} : une application peut fournir son propre
     * registre pour des besoins de test ou de composition avancée.
     *
     * @param modules                      fournisseur des beans {@link PivotModule} découverts
     *                                     (peut être vide)
     * @param catalogProperties            catalogue statique des modules réellement déployés
     * @param moduleActivationCacheService  cache-aside Redis (EN03.3) de résolution de
     *                                     l'activation par tenant, injecté {@code @Lazy} pour
     *                                     rompre le cycle de construction (ce cache délègue à
     *                                     {@link ModuleActivationService}, qui dépend lui-même
     *                                     de {@link ModuleRegistry})
     * @return registre immuable des modules disponibles
     */
    @Bean
    @ConditionalOnMissingBean
    public ModuleRegistry moduleRegistry(final ObjectProvider<PivotModule> modules,
                                          final ModuleCatalogProperties catalogProperties,
                                          @Lazy final ModuleActivationCacheService moduleActivationCacheService) {
        final List<PivotModule> discovered = modules.orderedStream().toList();
        final List<PivotModule> catalogued = ConfiguredPivotModuleFactory.fromCatalog(
                catalogProperties, moduleActivationCacheService);

        final List<PivotModule> all = new ArrayList<>(discovered);
        all.addAll(catalogued);

        final ModuleRegistry registry = new ModuleRegistry(all);
        LOG.info("event=MODULE_REGISTRY_INITIALIZED moduleCount={} discoveredBeans={} catalogued={}",
                registry.count(), discovered.size(), catalogued.size());
        return registry;
    }
}

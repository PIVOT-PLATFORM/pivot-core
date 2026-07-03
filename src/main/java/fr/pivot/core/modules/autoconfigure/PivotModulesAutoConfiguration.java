package fr.pivot.core.modules.autoconfigure;

import fr.pivot.core.modules.ModuleRegistry;
import fr.pivot.core.modules.PivotModule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

/**
 * Auto-configuration du système de modules PIVOT — exportée par
 * {@code fr.pivot:pivot-core-starter} via
 * {@code META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports}.
 *
 * <p>Permet à un repo module externe ({@code pivot-xxx-core}) d'enregistrer son
 * implémentation {@link PivotModule} par simple déclaration {@code @Bean}/{@code @Component} :
 * le {@link ModuleRegistry} est créé automatiquement avec tous les beans découverts,
 * sans aucune modification de pivot-core.
 */
@AutoConfiguration
public class PivotModulesAutoConfiguration {

    private static final Logger LOG = LoggerFactory.getLogger(PivotModulesAutoConfiguration.class);

    /**
     * Crée le registre des modules à partir de tous les beans {@link PivotModule}
     * du contexte Spring, dans l'ordre de priorité des beans.
     *
     * <p>{@code @ConditionalOnMissingBean} : une application peut fournir son propre
     * registre pour des besoins de test ou de composition avancée.
     *
     * @param modules fournisseur des beans {@link PivotModule} découverts (peut être vide)
     * @return registre immuable des modules disponibles
     */
    @Bean
    @ConditionalOnMissingBean
    public ModuleRegistry moduleRegistry(final ObjectProvider<PivotModule> modules) {
        final ModuleRegistry registry = new ModuleRegistry(modules.orderedStream().toList());
        LOG.info("event=MODULE_REGISTRY_INITIALIZED moduleCount={}", registry.count());
        return registry;
    }
}

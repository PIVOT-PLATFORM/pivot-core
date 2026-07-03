package fr.pivot.modules.registry;

import fr.pivot.core.modules.ModuleRegistry;
import fr.pivot.core.modules.PivotModule;
import fr.pivot.core.tenant.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Service centralisant la résolution des modules PIVOT pour un tenant donné.
 *
 * <p>S'appuie sur le {@link ModuleRegistry} (auto-découverte des beans {@link PivotModule}
 * du contexte Spring — voir
 * {@link fr.pivot.core.modules.autoconfigure.PivotModulesAutoConfiguration}). Pour chaque
 * module enregistré, le service évalue son activation via
 * {@link PivotModule#isEnabled(TenantContext)} et construit le {@link ModuleDto} correspondant.
 *
 * <p>Pas de {@code @Transactional} : opération de lecture pure en mémoire, aucun accès BDD.
 */
@Service
public class ModuleRegistryService {

    private static final Logger LOG = LoggerFactory.getLogger(ModuleRegistryService.class);

    private final ModuleRegistry moduleRegistry;

    /**
     * Construit le service avec le registre des modules découverts.
     *
     * @param moduleRegistry registre immuable des modules disponibles
     */
    public ModuleRegistryService(final ModuleRegistry moduleRegistry) {
        this.moduleRegistry = moduleRegistry;
    }

    /**
     * Retourne la liste des modules PIVOT avec leur statut pour le tenant courant.
     *
     * <p>Pour chaque module enregistré, évalue {@link PivotModule#isEnabled(TenantContext)} :
     * <ul>
     *   <li>{@code true}  → {@link ModuleStatus#ONLINE} + {@code enabled = true}</li>
     *   <li>{@code false} → {@link ModuleStatus#OFFLINE} + {@code enabled = false}</li>
     * </ul>
     *
     * @param ctx contexte tenant résolu depuis le token d'authentification
     * @return liste des {@link ModuleDto}, jamais {@code null} (vide si aucun module enregistré)
     */
    public List<ModuleDto> getModulesForTenant(final TenantContext ctx) {
        LOG.info("event=MODULE_REGISTRY_LIST tenantId={} userId={} role={}",
                ctx.tenantId(), ctx.userId(), ctx.role());

        return moduleRegistry.getModules().stream()
                .map(module -> {
                    final boolean enabled = module.isEnabled(ctx);
                    final ModuleStatus status = enabled ? ModuleStatus.ONLINE : ModuleStatus.OFFLINE;
                    return new ModuleDto(module.getId(), module.getName(), module.getVersion(), enabled, status);
                })
                .toList();
    }
}

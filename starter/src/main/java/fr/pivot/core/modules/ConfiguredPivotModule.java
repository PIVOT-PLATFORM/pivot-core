package fr.pivot.core.modules;

import fr.pivot.core.modules.cache.ModuleActivationCacheService;
import fr.pivot.core.tenant.TenantContext;

/**
 * {@link PivotModule} construit depuis une entrée statique de {@link ModuleCatalogProperties},
 * pour un module métier qui tourne comme service Spring Boot séparé (voir la Javadoc de
 * {@link ModuleCatalogProperties} pour le pourquoi) plutôt que comme bean auto-découvert dans
 * le process pivot-core.
 *
 * <p>{@link #isEnabled(TenantContext)} délègue entièrement à {@link ModuleActivationCacheService}
 * — cache-aside Redis (EN03.3) devant {@link ModuleActivationService}, plutôt qu'un appel direct
 * à {@link ModuleActivationService} — aucune logique d'activation dupliquée ici, seule l'identité
 * (id/nom/version) provient de la configuration statique.
 *
 * <p><strong>Dette S2 (2026-07-05 → corrigé)</strong> : avant ce correctif, cette classe appelait
 * {@link ModuleActivationService} directement, contournant totalement le cache Redis livré par
 * EN03.3 — chaque évaluation d'un module catalogué (donc chaque appel à {@code GET /api/modules},
 * EN03.4/US03.2.1) déclenchait une requête BDD, alors que {@code GET /api/modules/{id}/status}
 * (US03.2.2) utilisait déjà le cache depuis son introduction. Le cache reste inchangé
 * (même contrat {@code isEnabled(Long, String)}, même TTL) — seul le collaborateur change ici.
 */
final class ConfiguredPivotModule implements PivotModule {

    private final String id;
    private final String name;
    private final String version;
    private final String description;
    private final ModuleActivationCacheService moduleActivationCacheService;

    /**
     * Construit le module à partir d'une entrée de catalogue et du cache de résolution
     * d'activation.
     *
     * @param id                            identifiant technique du module
     * @param name                          nom affiché en UI
     * @param version                       version déployée
     * @param description                   description courte affichée en UI
     * @param moduleActivationCacheService  cache-aside Redis (EN03.3) devant
     *                                      {@link ModuleActivationService}, injecté {@code @Lazy}
     *                                      par l'auto-configuration pour éviter un cycle de
     *                                      construction avec {@link ModuleRegistry}, dont
     *                                      {@link ModuleActivationService} (donc transitivement
     *                                      {@link ModuleActivationCacheService}) dépend lui-même
     */
    ConfiguredPivotModule(final String id, final String name, final String version, final String description,
                           final ModuleActivationCacheService moduleActivationCacheService) {
        this.id = id;
        this.name = name;
        this.version = version;
        this.description = description;
        this.moduleActivationCacheService = moduleActivationCacheService;
    }

    @Override
    public String getId() {
        return id;
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public String getVersion() {
        return version;
    }

    @Override
    public String getDescription() {
        return description;
    }

    /**
     * Résout l'activation en délégant intégralement à {@link ModuleActivationCacheService}
     * (cache-aside Redis, EN03.3, lui-même délégant à {@link ModuleActivationService} en cas
     * de miss), qui porte déjà la sémantique complète (override SUPER_ADMIN prioritaire, puis
     * choix de l'admin de tenant, défaut désactivé).
     *
     * @param ctx contexte tenant résolu depuis le token porteur
     * @return {@code true} si le module est effectivement activé pour ce tenant ;
     *         {@code false} si {@code ctx} ne porte aucun tenant (ex. SUPER_ADMIN plateforme)
     */
    @Override
    public boolean isEnabled(final TenantContext ctx) {
        if (ctx.tenantId() == null) {
            return false;
        }
        return moduleActivationCacheService.isEnabled(ctx.tenantId(), id);
    }
}

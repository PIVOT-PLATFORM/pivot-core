package fr.pivot.core.modules;

import fr.pivot.core.tenant.TenantContext;

/**
 * {@link PivotModule} construit depuis une entrée statique de {@link ModuleCatalogProperties},
 * pour un module métier qui tourne comme service Spring Boot séparé (voir la Javadoc de
 * {@link ModuleCatalogProperties} pour le pourquoi) plutôt que comme bean auto-découvert dans
 * le process pivot-core.
 *
 * <p>{@link #isEnabled(TenantContext)} délègue entièrement à {@link ModuleActivationService} —
 * aucune logique d'activation dupliquée ici, seule l'identité (id/nom/version) provient de la
 * configuration statique.
 */
final class ConfiguredPivotModule implements PivotModule {

    private final String id;
    private final String name;
    private final String version;
    private final String description;
    private final ModuleActivationService moduleActivationService;

    /**
     * Construit le module à partir d'une entrée de catalogue et du service d'activation.
     *
     * @param id                       identifiant technique du module
     * @param name                     nom affiché en UI
     * @param version                  version déployée
     * @param description              description courte affichée en UI
     * @param moduleActivationService  service de résolution de l'activation par tenant
     *                                 (injecté {@code @Lazy} par l'auto-configuration pour éviter
     *                                 un cycle de construction avec {@link ModuleRegistry}, dont
     *                                 {@link ModuleActivationService} dépend lui-même)
     */
    ConfiguredPivotModule(final String id, final String name, final String version, final String description,
                           final ModuleActivationService moduleActivationService) {
        this.id = id;
        this.name = name;
        this.version = version;
        this.description = description;
        this.moduleActivationService = moduleActivationService;
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
     * Résout l'activation en délégant intégralement à {@link ModuleActivationService}, qui
     * porte déjà la sémantique complète (override SUPER_ADMIN prioritaire, puis choix de
     * l'admin de tenant, défaut désactivé).
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
        return moduleActivationService.isEnabled(ctx.tenantId(), id);
    }
}

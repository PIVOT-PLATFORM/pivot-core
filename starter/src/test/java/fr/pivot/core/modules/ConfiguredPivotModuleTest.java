package fr.pivot.core.modules;

import fr.pivot.core.modules.cache.ModuleActivationCacheService;
import fr.pivot.core.tenant.TenantContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests unitaires pour {@link ConfiguredPivotModule}.
 *
 * <p>Traçabilité « Dette S2 » : ce module délègue désormais à
 * {@link ModuleActivationCacheService} (cache-aside Redis, EN03.3) plutôt qu'à
 * {@link ModuleActivationService} directement — voir la Javadoc de classe de
 * {@link ConfiguredPivotModule} pour le contexte complet du correctif.
 */
@ExtendWith(MockitoExtension.class)
class ConfiguredPivotModuleTest {

    @Mock
    private ModuleActivationCacheService moduleActivationCacheService;

    private static final String MODULE_ID = "whiteboard";

    /**
     * Given un module construit depuis une entrée de catalogue,
     * when on lit son identité,
     * then id/nom/version reflètent exactement les valeurs passées au constructeur.
     */
    @Test
    void identity_shouldMatchConstructorArguments() {
        final ConfiguredPivotModule module = new ConfiguredPivotModule(
                MODULE_ID, "Tableau blanc collaboratif", "0.1.0",
                "Tableau blanc collaboratif temps réel", moduleActivationCacheService);

        assertThat(module.getId()).isEqualTo(MODULE_ID);
        assertThat(module.getName()).isEqualTo("Tableau blanc collaboratif");
        assertThat(module.getVersion()).isEqualTo("0.1.0");
        assertThat(module.getDescription()).isEqualTo("Tableau blanc collaboratif temps réel");
    }

    /**
     * Given un tenant valide,
     * when isEnabled() est appelé,
     * then la résolution est déléguée intégralement à ModuleActivationCacheService (cache-aside
     * Redis, EN03.3), sans logique dupliquée ici et sans jamais contourner le cache.
     */
    @Test
    void isEnabled_shouldDelegateToModuleActivationCacheService() {
        final ConfiguredPivotModule module = new ConfiguredPivotModule(
                MODULE_ID, "Tableau blanc collaboratif", "0.1.0",
                "Tableau blanc collaboratif temps réel", moduleActivationCacheService);
        final TenantContext ctx = new TenantContext(42L, "user-1", "ROLE_USER");
        when(moduleActivationCacheService.isEnabled(42L, MODULE_ID)).thenReturn(true);

        assertThat(module.isEnabled(ctx)).isTrue();
        verify(moduleActivationCacheService).isEnabled(42L, MODULE_ID);
    }

    /**
     * Given un cache qui résout le module comme désactivé,
     * when isEnabled() est appelé,
     * then le résultat reflète fidèlement la valeur retournée par le cache (pas d'inversion ou
     * de logique locale).
     */
    @Test
    void isEnabled_shouldReturnFalse_whenCacheResolvesDisabled() {
        final ConfiguredPivotModule module = new ConfiguredPivotModule(
                MODULE_ID, "Tableau blanc collaboratif", "0.1.0",
                "Tableau blanc collaboratif temps réel", moduleActivationCacheService);
        final TenantContext ctx = new TenantContext(42L, "user-1", "ROLE_USER");
        when(moduleActivationCacheService.isEnabled(42L, MODULE_ID)).thenReturn(false);

        assertThat(module.isEnabled(ctx)).isFalse();
    }

    /**
     * Given un contexte sans tenant (ex. SUPER_ADMIN plateforme),
     * when isEnabled() est appelé,
     * then le module est considéré désactivé sans même interroger ModuleActivationCacheService
     * (donc sans même toucher Redis).
     */
    @Test
    void isEnabled_shouldReturnFalse_whenContextHasNoTenant_withoutCallingCache() {
        final ConfiguredPivotModule module = new ConfiguredPivotModule(
                MODULE_ID, "Tableau blanc collaboratif", "0.1.0",
                "Tableau blanc collaboratif temps réel", moduleActivationCacheService);
        final TenantContext ctx = new TenantContext(null, "super-admin-1", "ROLE_SUPER_ADMIN");

        assertThat(module.isEnabled(ctx)).isFalse();
        verify(moduleActivationCacheService, never())
                .isEnabled(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }
}

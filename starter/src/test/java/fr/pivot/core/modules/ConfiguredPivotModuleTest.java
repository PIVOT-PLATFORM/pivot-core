package fr.pivot.core.modules;

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
 */
@ExtendWith(MockitoExtension.class)
class ConfiguredPivotModuleTest {

    @Mock
    private ModuleActivationService moduleActivationService;

    private static final String MODULE_ID = "whiteboard";

    /**
     * Given un module construit depuis une entrée de catalogue,
     * when on lit son identité,
     * then id/nom/version reflètent exactement les valeurs passées au constructeur.
     */
    @Test
    void identity_shouldMatchConstructorArguments() {
        final ConfiguredPivotModule module = new ConfiguredPivotModule(
                MODULE_ID, "Tableau blanc collaboratif", "0.1.0", moduleActivationService);

        assertThat(module.getId()).isEqualTo(MODULE_ID);
        assertThat(module.getName()).isEqualTo("Tableau blanc collaboratif");
        assertThat(module.getVersion()).isEqualTo("0.1.0");
    }

    /**
     * Given un tenant valide,
     * when isEnabled() est appelé,
     * then la résolution est déléguée intégralement à ModuleActivationService, sans logique
     * dupliquée ici.
     */
    @Test
    void isEnabled_shouldDelegateToModuleActivationService() {
        final ConfiguredPivotModule module = new ConfiguredPivotModule(
                MODULE_ID, "Tableau blanc collaboratif", "0.1.0", moduleActivationService);
        final TenantContext ctx = new TenantContext(42L, "user-1", "ROLE_USER");
        when(moduleActivationService.isEnabled(42L, MODULE_ID)).thenReturn(true);

        assertThat(module.isEnabled(ctx)).isTrue();
        verify(moduleActivationService).isEnabled(42L, MODULE_ID);
    }

    /**
     * Given un contexte sans tenant (ex. SUPER_ADMIN plateforme),
     * when isEnabled() est appelé,
     * then le module est considéré désactivé sans même interroger ModuleActivationService.
     */
    @Test
    void isEnabled_shouldReturnFalse_whenContextHasNoTenant_withoutCallingService() {
        final ConfiguredPivotModule module = new ConfiguredPivotModule(
                MODULE_ID, "Tableau blanc collaboratif", "0.1.0", moduleActivationService);
        final TenantContext ctx = new TenantContext(null, "super-admin-1", "ROLE_SUPER_ADMIN");

        assertThat(module.isEnabled(ctx)).isFalse();
        verify(moduleActivationService, never()).isEnabled(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }
}

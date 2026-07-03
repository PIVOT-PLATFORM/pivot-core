package fr.pivot.modules.api;

import fr.pivot.core.modules.ModuleActivation;
import fr.pivot.core.modules.ModuleActivationService;
import fr.pivot.core.modules.UnknownModuleException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests unitaires pour {@link AdminModuleActivationService}.
 *
 * <p>Traçabilité US03.1.1 / US03.1.2 — traduction des cas métier (déjà actif, module hors
 * registre) en exceptions dédiées. La vérification {@code @PreAuthorize("hasRole('ADMIN')")}
 * n'est pas exercée ici (pas de proxy Spring AOP dans un test Mockito pur) — elle est couverte
 * par {@code AdminModuleActivationIntegrationTest} (contexte Spring réel avec
 * {@code @EnableMethodSecurity}).
 */
@ExtendWith(MockitoExtension.class)
class AdminModuleActivationServiceTest {

    private static final Long TENANT_ID = 42L;
    private static final String MODULE_ID = "whiteboard";

    @Mock
    private ModuleActivationService moduleActivationService;

    private AdminModuleActivationService service;

    @BeforeEach
    void setUp() {
        service = new AdminModuleActivationService(moduleActivationService);
    }

    // ----------------------------------------------------------------
    // activate
    // ----------------------------------------------------------------

    @Test
    void activate_shouldDelegate_whenNotAlreadyActive() {
        final ModuleActivation activation = new ModuleActivation(TENANT_ID, MODULE_ID);
        activation.setEnabled(true);
        when(moduleActivationService.isEnabled(TENANT_ID, MODULE_ID)).thenReturn(false);
        when(moduleActivationService.activate(TENANT_ID, MODULE_ID)).thenReturn(activation);

        final ModuleActivation result = service.activate(TENANT_ID, MODULE_ID);

        assertThat(result.isEnabled()).isTrue();
        verify(moduleActivationService).activate(TENANT_ID, MODULE_ID);
    }

    @Test
    void activate_shouldThrowAlreadyActive_whenModuleAlreadyEnabled() {
        when(moduleActivationService.isEnabled(TENANT_ID, MODULE_ID)).thenReturn(true);

        assertThatThrownBy(() -> service.activate(TENANT_ID, MODULE_ID))
                .isInstanceOf(ModuleAlreadyActiveException.class);

        verify(moduleActivationService, never()).activate(TENANT_ID, MODULE_ID);
    }

    @Test
    void activate_shouldThrowNotInPlan_whenModuleUnknown() {
        when(moduleActivationService.isEnabled(TENANT_ID, "ghost")).thenReturn(false);
        when(moduleActivationService.activate(TENANT_ID, "ghost"))
                .thenThrow(new UnknownModuleException("ghost"));

        assertThatThrownBy(() -> service.activate(TENANT_ID, "ghost"))
                .isInstanceOf(ModuleNotInPlanException.class)
                .hasCauseInstanceOf(UnknownModuleException.class);
    }

    // ----------------------------------------------------------------
    // deactivate
    // ----------------------------------------------------------------

    @Test
    void deactivate_shouldDelegate_regardlessOfPriorState() {
        final ModuleActivation activation = new ModuleActivation(TENANT_ID, MODULE_ID);
        when(moduleActivationService.deactivate(TENANT_ID, MODULE_ID)).thenReturn(activation);

        final ModuleActivation result = service.deactivate(TENANT_ID, MODULE_ID);

        assertThat(result.isEnabled()).isFalse();
        verify(moduleActivationService).deactivate(TENANT_ID, MODULE_ID);
    }

    @Test
    void deactivate_shouldThrowNotInPlan_whenModuleUnknown() {
        when(moduleActivationService.deactivate(TENANT_ID, "ghost"))
                .thenThrow(new UnknownModuleException("ghost"));

        assertThatThrownBy(() -> service.deactivate(TENANT_ID, "ghost"))
                .isInstanceOf(ModuleNotInPlanException.class)
                .hasCauseInstanceOf(UnknownModuleException.class);
    }
}

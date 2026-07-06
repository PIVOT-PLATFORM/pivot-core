package fr.pivot.modules.api;

import fr.pivot.core.modules.ModuleActivationService;
import fr.pivot.core.modules.ModuleOverride;
import fr.pivot.core.modules.UnknownModuleException;
import fr.pivot.tenant.api.TenantNotFoundException;
import fr.pivot.tenant.entity.Tenant;
import fr.pivot.tenant.repository.TenantRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests unitaires pour {@link ModuleOverrideService}.
 *
 * <p>Traçabilité US03.3.2 :
 * <ul>
 *   <li>Validation d'existence du tenant — {@code tenantId} arbitraire (path variable
 *       SUPER_ADMIN, cross-tenant), jamais garanti exister ;</li>
 *   <li>Délégation intégrale à {@link ModuleActivationService} pour la mécanique
 *       override/résolution.</li>
 * </ul>
 *
 * <p>Le RBAC ({@code @PreAuthorize} porté par ce service) n'est pas exercé ici (mocks, hors
 * proxy Spring Security) — couvert par un test d'intégration dédié.
 */
@ExtendWith(MockitoExtension.class)
class ModuleOverrideServiceTest {

    private static final Long TENANT_ID = 7L;
    private static final String MODULE_ID = "whiteboard";

    @Mock
    private ModuleActivationService moduleActivationService;

    @Mock
    private TenantRepository tenantRepository;

    private ModuleOverrideService service;

    @BeforeEach
    void setUp() {
        service = new ModuleOverrideService(moduleActivationService, tenantRepository);
    }

    // ----------------------------------------------------------------
    // setOverride
    // ----------------------------------------------------------------

    @Test
    void setOverride_shouldDelegateAndReturnResult_whenTenantExists() {
        final Tenant tenant = mockTenant();
        when(tenantRepository.findById(TENANT_ID)).thenReturn(Optional.of(tenant));
        when(moduleActivationService.setOverride(TENANT_ID, MODULE_ID, true))
                .thenReturn(new ModuleOverride(TENANT_ID, MODULE_ID, true));

        final ModuleOverrideResult result = service.setOverride(TENANT_ID, MODULE_ID, true);

        assertThat(result.tenant()).isSameAs(tenant);
        assertThat(result.moduleId()).isEqualTo(MODULE_ID);
        assertThat(result.overridden()).isTrue();
        assertThat(result.enabled()).isTrue();
    }

    @Test
    void setOverride_shouldThrowTenantNotFound_beforeDelegating_whenTenantDoesNotExist() {
        when(tenantRepository.findById(TENANT_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.setOverride(TENANT_ID, MODULE_ID, true))
                .isInstanceOf(TenantNotFoundException.class);

        verify(moduleActivationService, never()).setOverride(any(), anyString(), anyBoolean());
    }

    @Test
    void setOverride_shouldPropagateUnknownModuleException_whenModuleNotRegistered() {
        final Tenant tenant = mockTenant();
        when(tenantRepository.findById(TENANT_ID)).thenReturn(Optional.of(tenant));
        when(moduleActivationService.setOverride(TENANT_ID, "ghost", true))
                .thenThrow(new UnknownModuleException("ghost"));

        assertThatThrownBy(() -> service.setOverride(TENANT_ID, "ghost", true))
                .isInstanceOf(UnknownModuleException.class);
    }

    // ----------------------------------------------------------------
    // removeOverride
    // ----------------------------------------------------------------

    @Test
    void removeOverride_shouldDelegateAndReturnResult_whenTenantExists() {
        final Tenant tenant = mockTenant();
        when(tenantRepository.findById(TENANT_ID)).thenReturn(Optional.of(tenant));
        when(moduleActivationService.removeOverride(TENANT_ID, MODULE_ID)).thenReturn(false);

        final ModuleOverrideResult result = service.removeOverride(TENANT_ID, MODULE_ID);

        assertThat(result.tenant()).isSameAs(tenant);
        assertThat(result.overridden()).isFalse();
        assertThat(result.enabled()).isFalse();
    }

    @Test
    void removeOverride_shouldThrowTenantNotFound_beforeDelegating_whenTenantDoesNotExist() {
        when(tenantRepository.findById(TENANT_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.removeOverride(TENANT_ID, MODULE_ID))
                .isInstanceOf(TenantNotFoundException.class);

        verify(moduleActivationService, never()).removeOverride(any(), anyString());
    }

    // ----------------------------------------------------------------
    // Helpers
    // ----------------------------------------------------------------

    private static Tenant mockTenant() {
        // getId() is not stubbed here: these tests assert `isSameAs(tenant)`, never call
        // Tenant#getId() directly (unlike SuperAdminModuleOverrideControllerTest, which maps
        // the result into a JSON body via ModuleOverrideResponse#from and does need it).
        return mock(Tenant.class);
    }
}

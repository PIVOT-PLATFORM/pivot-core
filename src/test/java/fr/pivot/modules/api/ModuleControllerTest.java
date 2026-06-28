package fr.pivot.modules.api;

import fr.pivot.auth.entity.User;
import fr.pivot.modules.registry.ModuleDto;
import fr.pivot.modules.registry.ModuleRegistryService;
import fr.pivot.modules.registry.ModuleStatus;
import fr.pivot.modules.registry.TenantContext;
import fr.pivot.tenant.entity.Tenant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests unitaires pour {@link ModuleController}.
 *
 * <p>Vérifie : extraction du contexte tenant, délégation au service,
 * rejet sur détails d'authentification invalides, et gestion du tenant absent.
 */
@ExtendWith(MockitoExtension.class)
class ModuleControllerTest {

    @Mock
    private ModuleRegistryService moduleRegistryService;

    private ModuleController controller;

    @BeforeEach
    void setUp() {
        controller = new ModuleController(moduleRegistryService);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    // ----------------------------------------------------------------
    // Happy path
    // ----------------------------------------------------------------

    @Test
    void shouldReturn200WithModules_whenAuthenticatedUserWithTenant() {
        setAuthentication(buildUser(1L, 42L, "ROLE_USER"));

        final ModuleDto dto = new ModuleDto("whiteboard", "Tableau blanc", "1.0.0", true, ModuleStatus.ONLINE);
        when(moduleRegistryService.getModulesForTenant(any())).thenReturn(List.of(dto));

        final ResponseEntity<List<ModuleDto>> response = controller.getModules();

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).hasSize(1);
        assertThat(response.getBody().get(0).id()).isEqualTo("whiteboard");
    }

    @Test
    void shouldReturn200WithEmptyList_whenNoModulesEnabled() {
        setAuthentication(buildUser(2L, 10L, "ROLE_ADMIN"));
        when(moduleRegistryService.getModulesForTenant(any())).thenReturn(List.of());

        final ResponseEntity<List<ModuleDto>> response = controller.getModules();

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isEmpty();
    }

    @Test
    void shouldPassTenantContextToService() {
        setAuthentication(buildUser(5L, 99L, "ROLE_ADMIN"));
        when(moduleRegistryService.getModulesForTenant(any())).thenReturn(List.of());

        controller.getModules();

        verify(moduleRegistryService).getModulesForTenant(any(TenantContext.class));
    }

    // ----------------------------------------------------------------
    // Security: invalid auth details
    // ----------------------------------------------------------------

    @Test
    void shouldReturn401_whenAuthDetailsNotUser() {
        final UsernamePasswordAuthenticationToken auth =
                new UsernamePasswordAuthenticationToken("principal", "credentials");
        auth.setDetails("not-a-user-object");
        SecurityContextHolder.getContext().setAuthentication(auth);

        final ResponseEntity<List<ModuleDto>> response = controller.getModules();

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        verify(moduleRegistryService, never()).getModulesForTenant(any());
    }

    @Test
    void shouldReturn401_whenAuthDetailsNull() {
        final UsernamePasswordAuthenticationToken auth =
                new UsernamePasswordAuthenticationToken("principal", "credentials");
        auth.setDetails(null);
        SecurityContextHolder.getContext().setAuthentication(auth);

        final ResponseEntity<List<ModuleDto>> response = controller.getModules();

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        verify(moduleRegistryService, never()).getModulesForTenant(any());
    }

    // ----------------------------------------------------------------
    // Null-safety: tenant absent
    // ----------------------------------------------------------------

    @Test
    void shouldNotThrow_whenUserTenantIsNull() {
        final User user = mock(User.class);
        when(user.getId()).thenReturn(3L);
        when(user.getRole()).thenReturn("ROLE_USER");
        when(user.getTenant()).thenReturn(null);
        setAuthentication(user);
        when(moduleRegistryService.getModulesForTenant(any())).thenReturn(List.of());

        final ResponseEntity<List<ModuleDto>> response = controller.getModules();

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    // ----------------------------------------------------------------
    // buildTenantContext helper
    // ----------------------------------------------------------------

    @Test
    void buildTenantContext_shouldProduceDeterministicUuid_forSameTenantId() {
        final User user1 = buildUser(1L, 7L, "ROLE_USER");
        final User user2 = buildUser(2L, 7L, "ROLE_ADMIN");

        final TenantContext ctx1 = ModuleController.buildTenantContext(user1);
        final TenantContext ctx2 = ModuleController.buildTenantContext(user2);

        assertThat(ctx1.tenantId()).isEqualTo(ctx2.tenantId());
    }

    @Test
    void longToUuid_shouldHandleNullId() {
        final UUID result = ModuleController.longToUuid(null);

        assertThat(result).isEqualTo(new UUID(0L, 0L));
    }

    @Test
    void longToUuid_shouldProduceDifferentUuids_forDifferentIds() {
        final UUID uuid1 = ModuleController.longToUuid(1L);
        final UUID uuid2 = ModuleController.longToUuid(2L);

        assertThat(uuid1).isNotEqualTo(uuid2);
    }

    // ----------------------------------------------------------------
    // Helpers
    // ----------------------------------------------------------------

    private static User buildUser(final Long userId, final Long tenantId, final String role) {
        final User user = mock(User.class);
        when(user.getId()).thenReturn(userId);
        when(user.getRole()).thenReturn(role);
        if (tenantId != null) {
            final Tenant tenant = mock(Tenant.class);
            when(tenant.getId()).thenReturn(tenantId);
            when(user.getTenant()).thenReturn(tenant);
        }
        return user;
    }

    private static void setAuthentication(final User user) {
        final UsernamePasswordAuthenticationToken auth =
                new UsernamePasswordAuthenticationToken(user.getId(), null);
        auth.setDetails(user);
        SecurityContextHolder.getContext().setAuthentication(auth);
    }
}

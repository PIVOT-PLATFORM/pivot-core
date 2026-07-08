package fr.pivot.modules.api;

import fr.pivot.auth.entity.User;
import fr.pivot.core.modules.ModuleRegistry;
import fr.pivot.core.modules.cache.ModuleActivationCacheService;
import fr.pivot.core.modules.UnknownModuleException;
import fr.pivot.modules.registry.ModuleDto;
import fr.pivot.modules.registry.ModuleRegistryService;
import fr.pivot.modules.registry.ModuleStatus;
import fr.pivot.modules.registry.ModuleStatusDto;
import fr.pivot.core.tenant.TenantContext;
import fr.pivot.tenant.entity.Tenant;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
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

    @Mock
    private ModuleRegistry moduleRegistry;

    @Mock
    private ModuleActivationCacheService moduleActivationCacheService;

    private ModuleController controller;

    @BeforeEach
    void setUp() {
        controller = new ModuleController(moduleRegistryService, moduleRegistry, moduleActivationCacheService);
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

        final ModuleDto dto = new ModuleDto(
                "whiteboard", "Tableau blanc", "Tableau blanc collaboratif temps réel", "1.0.0", true,
                ModuleStatus.ONLINE);
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
    void buildTenantContext_shouldUseTenantIdDirectly_withoutConversion() {
        final User user1 = buildUser(1L, 7L, "ROLE_USER");
        final User user2 = buildUser(2L, 7L, "ROLE_ADMIN");

        final TenantContext ctx1 = ModuleController.buildTenantContext(user1);
        final TenantContext ctx2 = ModuleController.buildTenantContext(user2);

        assertThat(ctx1.tenantId()).isEqualTo(7L);
        assertThat(ctx1.tenantId()).isEqualTo(ctx2.tenantId());
    }

    @Test
    void buildTenantContext_shouldReturnNullTenantId_whenUserHasNoTenant() {
        final User user = mock(User.class);
        when(user.getId()).thenReturn(3L);
        when(user.getRole()).thenReturn("ROLE_USER");
        when(user.getTenant()).thenReturn(null);

        final TenantContext ctx = ModuleController.buildTenantContext(user);

        assertThat(ctx.tenantId()).isNull();
    }

    // ----------------------------------------------------------------
    // GET /api/modules/{id}/status
    // ----------------------------------------------------------------

    @Test
    void getModuleStatus_shouldReturn200Enabled_whenModuleActivatedForTenant() {
        setAuthentication(buildUser(1L, 42L, "ROLE_USER"));
        when(moduleRegistry.isRegistered("whiteboard")).thenReturn(true);
        when(moduleActivationCacheService.isEnabled(42L, "whiteboard")).thenReturn(true);

        final ResponseEntity<ModuleStatusDto> response = controller.getModuleStatus("whiteboard");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().enabled()).isTrue();
    }

    @Test
    void getModuleStatus_shouldReturn200Disabled_whenModuleDeactivatedForTenant() {
        setAuthentication(buildUser(1L, 42L, "ROLE_USER"));
        when(moduleRegistry.isRegistered("whiteboard")).thenReturn(true);
        when(moduleActivationCacheService.isEnabled(42L, "whiteboard")).thenReturn(false);

        final ResponseEntity<ModuleStatusDto> response = controller.getModuleStatus("whiteboard");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().enabled()).isFalse();
    }

    @Test
    void getModuleStatus_shouldSetNoStoreCacheControl() {
        setAuthentication(buildUser(1L, 42L, "ROLE_USER"));
        when(moduleRegistry.isRegistered("whiteboard")).thenReturn(true);
        when(moduleActivationCacheService.isEnabled(42L, "whiteboard")).thenReturn(true);

        final ResponseEntity<ModuleStatusDto> response = controller.getModuleStatus("whiteboard");

        assertThat(response.getHeaders().getCacheControl()).contains("no-store");
    }

    @Test
    void getModuleStatus_shouldNeutralizeCrLf_whenLoggingUnknownModuleId() {
        // Security (CWE-117 / log forging): a moduleId crafted with CR/LF must not be able
        // to inject fake log lines into the (plain-text) application log.
        setAuthentication(buildUser(1L, 42L, "ROLE_USER"));
        final String maliciousId = "ghost\nevent=FAKE_ADMIN_LOGIN userId=999";
        when(moduleRegistry.isRegistered(maliciousId)).thenReturn(false);

        final Logger logger = (Logger) LoggerFactory.getLogger(ModuleController.class);
        final ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);

        try {
            assertThatThrownBy(() -> controller.getModuleStatus(maliciousId))
                    .isInstanceOf(UnknownModuleException.class);
        } finally {
            logger.detachAppender(appender);
        }

        assertThat(appender.list).isNotEmpty();
        appender.list.forEach(event -> {
            final String formatted = event.getFormattedMessage();
            assertThat(formatted).doesNotContain("\n").doesNotContain("\r");
        });
    }

    @Test
    void getModuleStatus_shouldThrowUnknownModuleException_whenModuleNotRegistered() {
        setAuthentication(buildUser(1L, 42L, "ROLE_USER"));
        when(moduleRegistry.isRegistered("ghost")).thenReturn(false);

        assertThatThrownBy(() -> controller.getModuleStatus("ghost"))
                .isInstanceOf(UnknownModuleException.class)
                .hasMessageContaining("ghost");

        verifyNoInteractions(moduleActivationCacheService);
    }

    @Test
    void getModuleStatus_shouldReturn401_whenAuthDetailsNotUser() {
        final UsernamePasswordAuthenticationToken auth =
                new UsernamePasswordAuthenticationToken("principal", "credentials");
        auth.setDetails("not-a-user-object");
        SecurityContextHolder.getContext().setAuthentication(auth);

        final ResponseEntity<ModuleStatusDto> response = controller.getModuleStatus("whiteboard");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        verifyNoInteractions(moduleRegistry, moduleActivationCacheService);
    }

    @Test
    void getModuleStatus_shouldNeverUseATenantIdOtherThanTheAuthenticatedUsersOwn() {
        // Security: the tenantId used to resolve activation comes exclusively from the
        // authenticated User's own tenant — there is no body/query param that could override it.
        setAuthentication(buildUser(1L, 42L, "ROLE_USER"));
        when(moduleRegistry.isRegistered("whiteboard")).thenReturn(true);
        when(moduleActivationCacheService.isEnabled(anyLong(), eq("whiteboard"))).thenReturn(true);

        controller.getModuleStatus("whiteboard");

        verify(moduleActivationCacheService).isEnabled(42L, "whiteboard");
    }

    // ----------------------------------------------------------------
    // Helpers
    // ----------------------------------------------------------------

    private static User buildUser(final Long userId, final Long tenantId, final String role) {
        // lenient(): this helper is shared across tests exercising different code paths
        // (getModules() uses getRole(); getModuleStatus() does not) — not every stub is
        // consumed by every caller, which is fine and not a test smell here.
        final User user = mock(User.class);
        lenient().when(user.getId()).thenReturn(userId);
        lenient().when(user.getRole()).thenReturn(role);
        if (tenantId != null) {
            final Tenant tenant = mock(Tenant.class);
            lenient().when(tenant.getId()).thenReturn(tenantId);
            lenient().when(user.getTenant()).thenReturn(tenant);
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

package fr.pivot.modules.api;

import fr.pivot.auth.entity.User;
import fr.pivot.auth.service.AuditService;
import fr.pivot.auth.web.GlobalExceptionHandler;
import fr.pivot.config.CookieHelper;
import fr.pivot.core.modules.UnknownModuleException;
import fr.pivot.tenant.api.TenantNotFoundException;
import fr.pivot.tenant.entity.Tenant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Tests unitaires (dispatch HTTP complet via MockMvc standalone, sans contexte Spring) pour
 * {@link SuperAdminModuleOverrideController} — US03.3.2 « SUPER_ADMIN active/désactive un module
 * par tenant (override) ».
 *
 * <p>Vérifie : délégation correcte au service mocké, forme JSON des réponses, validation bean
 * (400), mapping exception → statut (404 via {@link GlobalExceptionHandler} pour
 * {@link UnknownModuleException}, handler local pour {@link TenantNotFoundException}), le repli
 * 401 quand le contexte de sécurité ne porte aucun détail {@link User}, et l'audit ({@code
 * superAdminId} dans le meta JSON).
 *
 * <p>Le RBAC ({@code @PreAuthorize} porté par {@link ModuleOverrideService}) n'est pas exercé ici
 * (service mocké, hors proxy Spring Security) — couvert par un test d'intégration dédié.
 */
@ExtendWith(MockitoExtension.class)
class SuperAdminModuleOverrideControllerTest {

    private static final String ENDPOINT = "/superadmin/tenants/{tenantId}/modules/{moduleId}/override";
    private static final Long TENANT_ID = 7L;
    private static final String MODULE_ID = "whiteboard";

    @Mock
    private ModuleOverrideService moduleOverrideService;

    @Mock
    private AuditService auditService;

    @Mock
    private CookieHelper cookieHelper;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        final SuperAdminModuleOverrideController controller =
                new SuperAdminModuleOverrideController(moduleOverrideService, auditService, cookieHelper);
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
        lenient().when(cookieHelper.clientIp(any())).thenReturn("127.0.0.1");
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    // ----------------------------------------------------------------
    // POST .../override
    // ----------------------------------------------------------------

    @Test
    void setOverride_returns200_andLogsAuditWithSuperAdminId_whenSuccessful() throws Exception {
        setAuthenticatedSuperAdmin(99L);
        final Tenant tenant = mockTenant(TENANT_ID);
        when(moduleOverrideService.setOverride(TENANT_ID, MODULE_ID, true))
                .thenReturn(new ModuleOverrideResult(tenant, MODULE_ID, true, true));

        mockMvc.perform(post(ENDPOINT, TENANT_ID, MODULE_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"enabled\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tenantId").value(7))
                .andExpect(jsonPath("$.moduleId").value(MODULE_ID))
                .andExpect(jsonPath("$.overridden").value(true))
                .andExpect(jsonPath("$.enabled").value(true));

        verify(moduleOverrideService).setOverride(TENANT_ID, MODULE_ID, true);
        verify(auditService).log(any(User.class), eq(tenant), eq(AuditService.MODULE_OVERRIDE_SET),
                eq("127.0.0.1"), any(), contains("\"superAdminId\":99"));
    }

    @Test
    void setOverride_returns400_whenEnabledFieldMissing() throws Exception {
        setAuthenticatedSuperAdmin(1L);

        mockMvc.perform(post(ENDPOINT, TENANT_ID, MODULE_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());

        verify(moduleOverrideService, never()).setOverride(any(), any(), anyBoolean());
    }

    @Test
    void setOverride_returns401_whenSecurityContextHasNoUserDetails() throws Exception {
        SecurityContextHolder.clearContext();

        mockMvc.perform(post(ENDPOINT, TENANT_ID, MODULE_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"enabled\":true}"))
                .andExpect(status().isUnauthorized());

        verify(moduleOverrideService, never()).setOverride(any(), any(), anyBoolean());
    }

    @Test
    void setOverride_returns404_withExplicitError_whenTenantNotFound() throws Exception {
        setAuthenticatedSuperAdmin(1L);
        when(moduleOverrideService.setOverride(TENANT_ID, MODULE_ID, true))
                .thenThrow(new TenantNotFoundException(TENANT_ID));

        mockMvc.perform(post(ENDPOINT, TENANT_ID, MODULE_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"enabled\":true}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("TENANT_NOT_FOUND"));

        verify(auditService, never()).log(any(), any(), any(), any(), any(), any());
    }

    @Test
    void setOverride_returns404_withModuleNotFound_whenModuleUnregistered() throws Exception {
        setAuthenticatedSuperAdmin(1L);
        when(moduleOverrideService.setOverride(TENANT_ID, "ghost", true))
                .thenThrow(new UnknownModuleException("ghost"));

        mockMvc.perform(post(ENDPOINT, TENANT_ID, "ghost")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"enabled\":true}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("MODULE_NOT_FOUND"));

        verify(auditService, never()).log(any(), any(), any(), any(), any(), any());
    }

    // ----------------------------------------------------------------
    // DELETE .../override
    // ----------------------------------------------------------------

    @Test
    void removeOverride_returns200_andLogsAuditWithSuperAdminId_whenSuccessful() throws Exception {
        setAuthenticatedSuperAdmin(99L);
        final Tenant tenant = mockTenant(TENANT_ID);
        when(moduleOverrideService.removeOverride(TENANT_ID, MODULE_ID))
                .thenReturn(new ModuleOverrideResult(tenant, MODULE_ID, false, false));

        mockMvc.perform(delete(ENDPOINT, TENANT_ID, MODULE_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.overridden").value(false))
                .andExpect(jsonPath("$.enabled").value(false));

        verify(auditService).log(any(User.class), eq(tenant), eq(AuditService.MODULE_OVERRIDE_REMOVED),
                eq("127.0.0.1"), any(), contains("\"superAdminId\":99"));
    }

    @Test
    void removeOverride_returns401_whenSecurityContextHasNoUserDetails() throws Exception {
        SecurityContextHolder.clearContext();

        mockMvc.perform(delete(ENDPOINT, TENANT_ID, MODULE_ID))
                .andExpect(status().isUnauthorized());

        verify(moduleOverrideService, never()).removeOverride(any(), any());
    }

    @Test
    void removeOverride_returns404_withExplicitError_whenTenantNotFound() throws Exception {
        setAuthenticatedSuperAdmin(1L);
        when(moduleOverrideService.removeOverride(TENANT_ID, MODULE_ID))
                .thenThrow(new TenantNotFoundException(TENANT_ID));

        mockMvc.perform(delete(ENDPOINT, TENANT_ID, MODULE_ID))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("TENANT_NOT_FOUND"));
    }

    @Test
    void removeOverride_returns200_evenWhenNoOverrideExisted() throws Exception {
        // Idempotence documentée : DELETE ne signale jamais une absence d'override en erreur.
        setAuthenticatedSuperAdmin(1L);
        final Tenant tenant = mockTenant(TENANT_ID);
        when(moduleOverrideService.removeOverride(TENANT_ID, MODULE_ID))
                .thenReturn(new ModuleOverrideResult(tenant, MODULE_ID, false, true));

        mockMvc.perform(delete(ENDPOINT, TENANT_ID, MODULE_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(true));
    }

    // ----------------------------------------------------------------
    // Helpers
    // ----------------------------------------------------------------

    private static void setAuthenticatedSuperAdmin(final Long actorId) {
        final User actor = mock(User.class);
        lenient().when(actor.getId()).thenReturn(actorId);

        final UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken(
                "super-admin@pivot.test", null, List.of(new SimpleGrantedAuthority("ROLE_SUPER_ADMIN")));
        auth.setDetails(actor);
        SecurityContextHolder.getContext().setAuthentication(auth);
    }

    private static Tenant mockTenant(final Long tenantId) {
        final Tenant tenant = mock(Tenant.class);
        lenient().when(tenant.getId()).thenReturn(tenantId);
        return tenant;
    }
}

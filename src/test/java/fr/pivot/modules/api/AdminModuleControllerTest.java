package fr.pivot.modules.api;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import fr.pivot.auth.entity.User;
import fr.pivot.auth.service.AuditService;
import fr.pivot.config.CookieHelper;
import fr.pivot.core.modules.ModuleActivation;
import fr.pivot.core.modules.ModuleActivationService;
import fr.pivot.core.modules.ModuleRegistry;
import fr.pivot.core.modules.PivotModule;
import fr.pivot.tenant.entity.Tenant;
import jakarta.servlet.http.HttpServletRequest;
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
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests unitaires pour {@link AdminModuleController}.
 *
 * <p>Vérifie : extraction du contexte tenant/utilisateur, délégation au service, traduction
 * des exceptions métier en codes HTTP dédiés, rejet sur détails d'authentification invalides
 * ou tenant absent, et déclenchement de l'audit sur succès.
 *
 * <p>Le RBAC ({@code @PreAuthorize}), qu'il soit porté par {@link AdminModuleActivationService}
 * (activate/deactivate) ou directement par {@link AdminModuleController#list()}, n'est pas
 * exercé ici (contrôleur instancié directement, hors proxy Spring) — couvert par
 * {@code AdminModuleActivationIntegrationTest}.
 */
@ExtendWith(MockitoExtension.class)
class AdminModuleControllerTest {

    private static final String MODULE_ID = "whiteboard";

    @Mock
    private AdminModuleActivationService adminModuleActivationService;

    @Mock
    private ModuleActivationService moduleActivationService;

    @Mock
    private ModuleRegistry moduleRegistry;

    @Mock
    private AuditService auditService;

    @Mock
    private CookieHelper cookieHelper;

    private AdminModuleController controller;
    private HttpServletRequest request;

    @BeforeEach
    void setUp() {
        controller = new AdminModuleController(
                adminModuleActivationService, moduleActivationService, moduleRegistry, auditService, cookieHelper);
        request = mock(HttpServletRequest.class);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    // ----------------------------------------------------------------
    // activate
    // ----------------------------------------------------------------

    @Test
    void activate_shouldReturn200AndAudit_whenSuccess() {
        setAuthentication(buildUser(1L, 42L, "ROLE_ADMIN"));
        when(cookieHelper.clientIp(request)).thenReturn("127.0.0.1");
        when(request.getHeader("User-Agent")).thenReturn("test-agent");
        final ModuleActivation activation = new ModuleActivation(42L, MODULE_ID);
        activation.setEnabled(true);
        when(adminModuleActivationService.activate(42L, MODULE_ID)).thenReturn(activation);

        final ResponseEntity<Map<String, Object>> response = controller.activate(MODULE_ID, request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).containsEntry("id", MODULE_ID).containsEntry("enabled", true);
        verify(adminModuleActivationService).activate(42L, MODULE_ID);
        verify(auditService).log(any(User.class), eq(AuditService.MODULE_ACTIVATED), eq("127.0.0.1"), eq("test-agent"));
    }

    @Test
    void activate_shouldReturn401_whenAuthDetailsNotUser() {
        final UsernamePasswordAuthenticationToken auth =
                new UsernamePasswordAuthenticationToken("principal", "credentials");
        auth.setDetails("not-a-user-object");
        SecurityContextHolder.getContext().setAuthentication(auth);

        final ResponseEntity<Map<String, Object>> response = controller.activate(MODULE_ID, request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        verify(adminModuleActivationService, never()).activate(any(), anyString());
        verify(auditService, never()).log(any(), anyString(), anyString(), anyString());
    }

    @Test
    void activate_shouldReturn401_whenUserHasNoTenant() {
        final User user = mock(User.class);
        when(user.getId()).thenReturn(9L);
        when(user.getTenant()).thenReturn(null);
        setAuthentication(user);

        final ResponseEntity<Map<String, Object>> response = controller.activate(MODULE_ID, request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        verify(adminModuleActivationService, never()).activate(any(), anyString());
    }

    @Test
    void activate_shouldNeutralizeCrLf_whenLoggingMaliciousModuleId() {
        // Security (CWE-117 / log forging): a moduleId crafted with CR/LF must not be able
        // to inject fake log lines into the (plain-text) application log.
        setAuthentication(buildUser(1L, 42L, "ROLE_ADMIN"));
        final String maliciousId = "ghost\nevent=FAKE_ADMIN_LOGIN userId=999";
        final ModuleActivation activation = new ModuleActivation(42L, maliciousId);
        activation.setEnabled(true);
        when(adminModuleActivationService.activate(42L, maliciousId)).thenReturn(activation);

        final Logger logger = (Logger) LoggerFactory.getLogger(AdminModuleController.class);
        final ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);

        try {
            controller.activate(maliciousId, request);
        } finally {
            logger.detachAppender(appender);
        }

        assertThat(appender.list).isNotEmpty();
        appender.list.forEach(event -> {
            final String formatted = event.getFormattedMessage();
            assertThat(formatted).doesNotContain("\n").doesNotContain("\r");
        });
    }

    // ----------------------------------------------------------------
    // deactivate
    // ----------------------------------------------------------------

    @Test
    void deactivate_shouldReturn200AndAudit_whenSuccess() {
        setAuthentication(buildUser(1L, 42L, "ROLE_ADMIN"));
        when(cookieHelper.clientIp(request)).thenReturn("127.0.0.1");
        when(request.getHeader("User-Agent")).thenReturn("test-agent");
        final ModuleActivation activation = new ModuleActivation(42L, MODULE_ID);
        when(adminModuleActivationService.deactivate(42L, MODULE_ID)).thenReturn(activation);

        final ResponseEntity<Map<String, Object>> response = controller.deactivate(MODULE_ID, request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).containsEntry("id", MODULE_ID).containsEntry("enabled", false);
        verify(auditService).log(any(User.class), eq(AuditService.MODULE_DEACTIVATED), eq("127.0.0.1"), eq("test-agent"));
    }

    @Test
    void deactivate_shouldReturn401_whenAuthDetailsNotUser() {
        final UsernamePasswordAuthenticationToken auth =
                new UsernamePasswordAuthenticationToken("principal", "credentials");
        auth.setDetails(null);
        SecurityContextHolder.getContext().setAuthentication(auth);

        final ResponseEntity<Map<String, Object>> response = controller.deactivate(MODULE_ID, request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        verify(adminModuleActivationService, never()).deactivate(any(), anyString());
    }

    // ----------------------------------------------------------------
    // list
    // ----------------------------------------------------------------

    @Test
    void list_shouldReturn200WithModulesAndEmptyDescription() {
        setAuthentication(buildUser(1L, 42L, "ROLE_ADMIN"));
        final PivotModule module = stubModule(MODULE_ID, "Tableau blanc");
        when(moduleRegistry.getModules()).thenReturn(List.of(module));
        when(moduleActivationService.isEnabled(42L, MODULE_ID)).thenReturn(true);

        final ResponseEntity<List<AdminModuleDto>> response = controller.list();

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).hasSize(1);
        final AdminModuleDto dto = response.getBody().get(0);
        assertThat(dto.id()).isEqualTo(MODULE_ID);
        assertThat(dto.name()).isEqualTo("Tableau blanc");
        assertThat(dto.enabled()).isTrue();
        assertThat(dto.description()).isEmpty();
    }

    @Test
    void list_shouldReturn401_whenAuthDetailsNotUser() {
        final UsernamePasswordAuthenticationToken auth =
                new UsernamePasswordAuthenticationToken("principal", "credentials");
        auth.setDetails(null);
        SecurityContextHolder.getContext().setAuthentication(auth);

        final ResponseEntity<List<AdminModuleDto>> response = controller.list();

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        verify(moduleRegistry, never()).getModules();
    }

    // ----------------------------------------------------------------
    // Exception translation — local handlers
    // ----------------------------------------------------------------

    @Test
    void handleAlreadyActive_shouldReturn409WithBody() {
        final ResponseEntity<Map<String, Object>> response =
                controller.handleAlreadyActive(new ModuleAlreadyActiveException(MODULE_ID));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody()).containsEntry("error", "MODULE_ALREADY_ACTIVE");
    }

    @Test
    void handleNotInPlan_shouldReturn403WithBody() {
        final ResponseEntity<Map<String, Object>> response =
                controller.handleNotInPlan(new ModuleNotInPlanException(MODULE_ID, new RuntimeException("cause")));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(response.getBody()).containsEntry("error", "MODULE_NOT_IN_PLAN");
    }

    // ----------------------------------------------------------------
    // Helpers
    // ----------------------------------------------------------------

    private static PivotModule stubModule(final String id, final String name) {
        final PivotModule module = mock(PivotModule.class);
        when(module.getId()).thenReturn(id);
        when(module.getName()).thenReturn(name);
        return module;
    }

    // "role" only documents the caller's intent (ROLE_ADMIN) for readability — the RBAC check
    // itself is not exercised here (AdminModuleActivationService is mocked); it is covered by
    // AdminModuleActivationIntegrationTest against a real Spring Security proxy.
    private static User buildUser(final Long userId, final Long tenantId, final String role) {
        final User user = mock(User.class);
        when(user.getId()).thenReturn(userId);
        final Tenant tenant = mock(Tenant.class);
        when(tenant.getId()).thenReturn(tenantId);
        when(user.getTenant()).thenReturn(tenant);
        return user;
    }

    private static void setAuthentication(final User user) {
        final UsernamePasswordAuthenticationToken auth =
                new UsernamePasswordAuthenticationToken(user.getId(), null);
        auth.setDetails(user);
        SecurityContextHolder.getContext().setAuthentication(auth);
    }
}

package fr.pivot.auth.controller;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import fr.pivot.auth.dto.AdminUserDto;
import fr.pivot.auth.dto.AssignableRole;
import fr.pivot.auth.dto.UserStatus;
import fr.pivot.auth.entity.User;
import fr.pivot.auth.exception.AdminUserNotFoundException;
import fr.pivot.auth.exception.InvalidUserFilterException;
import fr.pivot.auth.exception.SelfRoleChangeForbiddenException;
import fr.pivot.auth.service.AdminUserService;
import fr.pivot.auth.service.AuditService;
import fr.pivot.config.CookieHelper;
import fr.pivot.tenant.entity.Tenant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Tests unitaires pour {@link AdminUserController} — US06.1.1.
 *
 * <p>Vérifie : extraction du contexte tenant/utilisateur, délégation au service, traduction
 * des exceptions métier, rejet sur détails d'authentification invalides ou tenant absent.
 *
 * <p>Le RBAC ({@code @PreAuthorize} porté par {@link AdminUserService}) n'est pas exercé ici
 * (contrôleur instancié directement, hors proxy Spring) — couvert par
 * {@code AdminUserIntegrationTest}.
 */
@ExtendWith(MockitoExtension.class)
class AdminUserControllerTest {

    @Mock
    private AdminUserService adminUserService;

    @Mock
    private AuditService auditService;

    @Mock
    private CookieHelper cookieHelper;

    private AdminUserController controller;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        controller = new AdminUserController(adminUserService, auditService, cookieHelper);
        // Exception handlers (@ExceptionHandler) are declared directly on the controller, so a
        // standalone MockMvc dispatch (no external @RestControllerAdvice) already resolves them —
        // used by the PATCH .../role tests below to exercise real HTTP-level @Valid/enum
        // deserialization, which a direct method call cannot.
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
        lenient().when(cookieHelper.clientIp(any())).thenReturn("127.0.0.1");
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    // ----------------------------------------------------------------
    // AC : GET /api/admin/users retourne la liste paginée du tenant
    // ----------------------------------------------------------------

    @Test
    void ac0611_01_shouldReturn200AndDelegateToService_whenCallerIsRoleAdmin() {
        setAuthentication(buildUser(1L, 42L));
        final AdminUserDto dto = new AdminUserDto(
                9L, "alice@pivot.test", "Alice", "Martin", "ROLE_USER", UserStatus.ACTIVE, Instant.now());
        final Page<AdminUserDto> page = new PageImpl<>(List.of(dto));
        when(adminUserService.listUsers(42L, 0, 20, null, null, null)).thenReturn(page);

        final ResponseEntity<Page<AdminUserDto>> response = controller.list(0, 20, null, null, null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getContent()).containsExactly(dto);
        verify(adminUserService).listUsers(42L, 0, 20, null, null, null);
    }

    @Test
    void ac0611_02_shouldPassFiltersThrough_toService() {
        setAuthentication(buildUser(1L, 42L));
        when(adminUserService.listUsers(eq(42L), eq(1), eq(50), eq("ROLE_ADMIN"), eq("ACTIVE"), eq("alice")))
                .thenReturn(new PageImpl<>(List.of()));

        controller.list(1, 50, "ROLE_ADMIN", "ACTIVE", "alice");

        verify(adminUserService).listUsers(42L, 1, 50, "ROLE_ADMIN", "ACTIVE", "alice");
    }

    // ----------------------------------------------------------------
    // AC sécurité : tenantId jamais accepté depuis un paramètre — extrait du token porteur
    // ----------------------------------------------------------------

    @Test
    void ac0611Sec01_shouldNeverUseAnythingButTokenTenant_regardlessOfCallerIntent() {
        // Le tenant B tente implicitement une fuite : peu importe l'ordre des paramètres du
        // contrôleur, aucun paramètre "tenantId" n'existe dans la signature — seul le tenant
        // résolu depuis le token authentifié (ici 42L) est transmis au service.
        setAuthentication(buildUser(1L, 42L));
        when(adminUserService.listUsers(eq(42L), anyInt(), anyInt(),
                isNull(), isNull(), isNull())).thenReturn(new PageImpl<>(List.of()));

        controller.list(0, 20, null, null, null);

        verify(adminUserService).listUsers(eq(42L), anyInt(), anyInt(),
                isNull(), isNull(), isNull());
    }

    // ----------------------------------------------------------------
    // AC erreur : contexte d'authentification invalide
    // ----------------------------------------------------------------

    @Test
    void ac0611Err01_shouldReturn401_whenAuthDetailsNotUser() {
        final UsernamePasswordAuthenticationToken auth =
                new UsernamePasswordAuthenticationToken("principal", "credentials");
        auth.setDetails("not-a-user-object");
        SecurityContextHolder.getContext().setAuthentication(auth);

        final ResponseEntity<Page<AdminUserDto>> response = controller.list(0, 20, null, null, null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        verify(adminUserService, never()).listUsers(any(), anyInt(), anyInt(), any(), any(), any());
    }

    @Test
    void ac0611Err02_shouldReturn401_whenUserHasNoTenant() {
        final User user = mock(User.class);
        when(user.getId()).thenReturn(9L);
        when(user.getTenant()).thenReturn(null);
        setAuthentication(user);

        final ResponseEntity<Page<AdminUserDto>> response = controller.list(0, 20, null, null, null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        verify(adminUserService, never()).listUsers(any(), anyInt(), anyInt(), any(), any(), any());
    }

    @Test
    void ac0611Err03_shouldReturn401_whenNoAuthentication() {
        SecurityContextHolder.clearContext();

        final ResponseEntity<Page<AdminUserDto>> response = controller.list(0, 20, null, null, null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    // ----------------------------------------------------------------
    // AC erreur : filtre status invalide -> 400
    // ----------------------------------------------------------------

    @Test
    void ac0611Err04_handleInvalidFilter_shouldReturn400WithBody() {
        final ResponseEntity<Map<String, Object>> response =
                controller.handleInvalidFilter(new InvalidUserFilterException("status", "bogus"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody())
                .containsEntry("error", "INVALID_FILTER")
                .containsEntry("field", "status");
    }

    @Test
    void ac0611Err05_handleInvalidFilter_shouldNeutralizeCrLf_whenLoggingMaliciousValue() {
        // Security (CWE-117 / log forging): a filter value crafted with CR/LF must not be able
        // to inject fake log lines into the (plain-text) application log.
        final String maliciousValue = "bogus\nevent=FAKE_ADMIN_ACTION userId=999";

        final Logger logger = (Logger) LoggerFactory.getLogger(AdminUserController.class);
        final ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);

        try {
            controller.handleInvalidFilter(new InvalidUserFilterException("status", maliciousValue));
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
    // PATCH /api/admin/users/{userId}/role — US06.1.3
    // ----------------------------------------------------------------

    @Test
    void ac0613_01_returns200_delegatesToService_andLogsAuditEvent_whenRoleChanged() throws Exception {
        setAuthentication(buildUser(1L, 42L));
        final AdminUserDto updated = new AdminUserDto(
                9L, "bob@pivot.test", "Bob", "Dupont", "ROLE_ADMIN", UserStatus.ACTIVE, Instant.now());
        when(adminUserService.updateRole(42L, 1L, 9L, AssignableRole.ROLE_ADMIN)).thenReturn(updated);

        mockMvc.perform(patch("/api/admin/users/{userId}/role", 9L)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"role\":\"ROLE_ADMIN\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(9))
                .andExpect(jsonPath("$.role").value("ROLE_ADMIN"));

        verify(adminUserService).updateRole(42L, 1L, 9L, AssignableRole.ROLE_ADMIN);
        verify(auditService).log(any(User.class), any(Tenant.class), eq(AuditService.USER_ROLE_CHANGED),
                eq("127.0.0.1"), any(), any());
    }

    // ----------------------------------------------------------------
    // AC : validation stricte du rôle dans le DTO — 400 sur valeur absente/inconnue
    // ----------------------------------------------------------------

    @Test
    void ac0613Err01_returns400_whenRoleFieldMissing() throws Exception {
        setAuthentication(buildUser(1L, 42L));

        mockMvc.perform(patch("/api/admin/users/{userId}/role", 9L)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());

        verify(adminUserService, never()).updateRole(any(), any(), any(), any());
    }

    @Test
    void ac0613Err02_returns400_whenRoleIsSuperAdmin() throws Exception {
        setAuthentication(buildUser(1L, 42L));

        mockMvc.perform(patch("/api/admin/users/{userId}/role", 9L)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"role\":\"ROLE_SUPER_ADMIN\"}"))
                .andExpect(status().isBadRequest());

        verify(adminUserService, never()).updateRole(any(), any(), any(), any());
    }

    @Test
    void ac0613Err03_returns400_whenRoleIsUnknownValue() throws Exception {
        setAuthentication(buildUser(1L, 42L));

        mockMvc.perform(patch("/api/admin/users/{userId}/role", 9L)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"role\":\"bogus\"}"))
                .andExpect(status().isBadRequest());

        verify(adminUserService, never()).updateRole(any(), any(), any(), any());
    }

    // ----------------------------------------------------------------
    // AC sécurité : auto-rétrogradation interdite -> 403
    // ----------------------------------------------------------------

    @Test
    void ac0613Sec01_returns403_whenServiceRejectsSelfRoleChange() throws Exception {
        setAuthentication(buildUser(1L, 42L));
        when(adminUserService.updateRole(42L, 1L, 1L, AssignableRole.ROLE_USER))
                .thenThrow(new SelfRoleChangeForbiddenException(1L));

        mockMvc.perform(patch("/api/admin/users/{userId}/role", 1L)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"role\":\"ROLE_USER\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("SELF_ROLE_CHANGE_FORBIDDEN"));

        verify(auditService, never()).log(any(), any(), any(), any(), any(), any());
    }

    // ----------------------------------------------------------------
    // AC sécurité : isolation tenant -> 404 (jamais 403) sur userId cross-tenant/inexistant
    // ----------------------------------------------------------------

    @Test
    void ac0613Sec02_returns404_whenServiceReportsUserNotFound() throws Exception {
        setAuthentication(buildUser(1L, 42L));
        when(adminUserService.updateRole(42L, 1L, 999L, AssignableRole.ROLE_ADMIN))
                .thenThrow(new AdminUserNotFoundException(999L));

        mockMvc.perform(patch("/api/admin/users/{userId}/role", 999L)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"role\":\"ROLE_ADMIN\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("USER_NOT_FOUND"));

        verify(auditService, never()).log(any(), any(), any(), any(), any(), any());
    }

    // ----------------------------------------------------------------
    // AC erreur : contexte d'authentification invalide -> 401
    // ----------------------------------------------------------------

    @Test
    void ac0613Err04_returns401_whenNoAuthentication() throws Exception {
        SecurityContextHolder.clearContext();

        mockMvc.perform(patch("/api/admin/users/{userId}/role", 9L)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"role\":\"ROLE_ADMIN\"}"))
                .andExpect(status().isUnauthorized());

        verify(adminUserService, never()).updateRole(any(), any(), any(), any());
    }

    // ----------------------------------------------------------------
    // Helpers
    // ----------------------------------------------------------------

    private static User buildUser(final Long userId, final Long tenantId) {
        final User user = mock(User.class);
        // Lenient: the PATCH .../role 400 tests (invalid/missing "role") never reach
        // resolveActor() at all — @Valid/enum binding fails before the controller method body
        // runs — so these stubs go unused on that path without it being a test smell.
        lenient().when(user.getId()).thenReturn(userId);
        final Tenant tenant = mock(Tenant.class);
        lenient().when(tenant.getId()).thenReturn(tenantId);
        lenient().when(user.getTenant()).thenReturn(tenant);
        return user;
    }

    private static void setAuthentication(final User user) {
        final UsernamePasswordAuthenticationToken auth =
                new UsernamePasswordAuthenticationToken(user.getId(), null);
        auth.setDetails(user);
        SecurityContextHolder.getContext().setAuthentication(auth);
    }
}

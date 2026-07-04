package fr.pivot.auth.controller;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import fr.pivot.auth.dto.AdminUserDto;
import fr.pivot.auth.dto.UserStatus;
import fr.pivot.auth.entity.User;
import fr.pivot.auth.exception.InvalidUserFilterException;
import fr.pivot.auth.service.AdminUserService;
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
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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

    private AdminUserController controller;

    @BeforeEach
    void setUp() {
        controller = new AdminUserController(adminUserService);
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
    // Helpers
    // ----------------------------------------------------------------

    private static User buildUser(final Long userId, final Long tenantId) {
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

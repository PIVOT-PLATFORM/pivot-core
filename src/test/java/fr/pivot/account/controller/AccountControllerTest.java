package fr.pivot.account.controller;

import fr.pivot.account.dto.ProfileDto;
import fr.pivot.account.dto.ProfileUpdateRequest;
import fr.pivot.account.exception.AvatarTooLargeException;
import fr.pivot.account.exception.InvalidAvatarFormatException;
import fr.pivot.account.exception.InvalidProfileNameException;
import fr.pivot.account.service.ProfileService;
import fr.pivot.auth.entity.User;
import fr.pivot.auth.service.AuditService;
import fr.pivot.config.CookieHelper;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartFile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests unitaires pour {@link AccountController} (US02.1.1).
 *
 * <p>Vérifie : extraction du contexte utilisateur depuis le contexte de sécurité, délégation
 * à {@link ProfileService}, traduction des exceptions métier en codes HTTP dédiés, rejet sur
 * détails d'authentification invalides, et déclenchement de l'audit sur succès.
 *
 * <p>Traçabilité :
 * <ul>
 *   <li>AC "GET retourne le profil" — {@code ac0211_01_*}</li>
 *   <li>AC "PATCH met à jour prénom/nom" — {@code ac0211_02_*}</li>
 *   <li>AC "upload avatar" — {@code ac0211_avatar_*}</li>
 *   <li>Security "identité exclusivement depuis le token porteur" — {@code *_401_*}</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class AccountControllerTest {

    @Mock
    private ProfileService profileService;

    @Mock
    private AuditService auditService;

    @Mock
    private CookieHelper cookieHelper;

    private AccountController controller;
    private HttpServletRequest request;

    @BeforeEach
    void setUp() {
        controller = new AccountController(profileService, auditService, cookieHelper);
        request = mock(HttpServletRequest.class);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    // ----------------------------------------------------------------
    // getProfile
    // ----------------------------------------------------------------

    @Test
    void ac0211_01_getProfile_returns200WithDto() {
        final User user = buildUser(1L);
        setAuthentication(user);
        final ProfileDto dto = new ProfileDto("Alice", "Martin", "alice@pivot.test", null);
        when(profileService.getProfile(user)).thenReturn(dto);

        final ResponseEntity<ProfileDto> response = controller.getProfile();

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isEqualTo(dto);
    }

    @Test
    void getProfile_returns401_whenAuthDetailsNotUser() {
        final UsernamePasswordAuthenticationToken auth =
                new UsernamePasswordAuthenticationToken("principal", "credentials");
        auth.setDetails("not-a-user-object");
        SecurityContextHolder.getContext().setAuthentication(auth);

        final ResponseEntity<ProfileDto> response = controller.getProfile();

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        verify(profileService, never()).getProfile(any());
    }

    @Test
    void getProfile_returns401_whenNoAuthentication() {
        final ResponseEntity<ProfileDto> response = controller.getProfile();

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    // ----------------------------------------------------------------
    // updateProfile
    // ----------------------------------------------------------------

    @Test
    void ac0211_02_updateProfile_returns200AndAudits_whenSuccess() {
        final User user = buildUser(1L);
        setAuthentication(user);
        when(cookieHelper.clientIp(request)).thenReturn("127.0.0.1");
        when(request.getHeader("User-Agent")).thenReturn("test-agent");
        final Map<String, Object> body = Map.of("firstName", "Bob", "lastName", "Dupont");
        final ProfileDto dto = new ProfileDto("Bob", "Dupont", "alice@pivot.test", null);
        when(profileService.updateProfile(eq(user), eq(new ProfileUpdateRequest("Bob", "Dupont"))))
                .thenReturn(dto);

        final ResponseEntity<ProfileDto> response = controller.updateProfile(body, request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isEqualTo(dto);
        verify(auditService).log(eq(user), eq(AuditService.PROFILE_UPDATED), eq("127.0.0.1"), eq("test-agent"));
    }

    @Test
    void updateProfile_returns401_whenAuthDetailsNotUser() {
        final UsernamePasswordAuthenticationToken auth =
                new UsernamePasswordAuthenticationToken("principal", "credentials");
        auth.setDetails(null);
        SecurityContextHolder.getContext().setAuthentication(auth);

        final ResponseEntity<ProfileDto> response =
                controller.updateProfile(Map.of("firstName", "Bob", "lastName", "Dupont"), request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        verify(profileService, never()).updateProfile(any(), any());
    }

    @Test
    void ac0211_sec_updateProfile_throwsEmailFieldNotAllowed_whenBodyContainsEmail() {
        final User user = buildUser(1L);
        setAuthentication(user);
        final Map<String, Object> body =
                Map.of("firstName", "Bob", "lastName", "Dupont", "email", "hacker@evil.test");

        assertThatThrownBy(() -> controller.updateProfile(body, request))
                .isInstanceOf(fr.pivot.account.exception.EmailFieldNotAllowedException.class);

        verify(profileService, never()).updateProfile(any(), any());
    }

    @Test
    void handleInvalidName_shouldReturn400WithBody() {
        final ResponseEntity<java.util.Map<String, Object>> response =
                controller.handleInvalidName(new InvalidProfileNameException("blank"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).containsEntry("error", "INVALID_NAME");
    }

    @Test
    void handleEmailFieldNotAllowed_shouldReturn400WithBody() {
        final ResponseEntity<java.util.Map<String, Object>> response =
                controller.handleEmailFieldNotAllowed(new fr.pivot.account.exception.EmailFieldNotAllowedException());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).containsEntry("error", "EMAIL_CHANGE_NOT_ALLOWED");
    }

    // ----------------------------------------------------------------
    // uploadAvatar
    // ----------------------------------------------------------------

    @Test
    void ac0211_avatar_uploadAvatar_returns200AndAudits_whenSuccess() {
        final User user = buildUser(1L);
        setAuthentication(user);
        when(cookieHelper.clientIp(request)).thenReturn("127.0.0.1");
        when(request.getHeader("User-Agent")).thenReturn("test-agent");
        final MultipartFile file = new MockMultipartFile("file", "avatar.jpg", "image/jpeg", new byte[]{1, 2, 3});
        final ProfileDto dto = new ProfileDto("Alice", "Martin", "alice@pivot.test", "/api/avatars/1/uuid.jpg");
        when(profileService.updateAvatar(eq(user), eq(file))).thenReturn(dto);

        final ResponseEntity<ProfileDto> response = controller.uploadAvatar(file, request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().avatarUrl()).isEqualTo("/api/avatars/1/uuid.jpg");
        verify(auditService).log(eq(user), eq(AuditService.AVATAR_UPDATED), anyString(), anyString());
    }

    @Test
    void uploadAvatar_returns401_whenAuthDetailsNotUser() {
        final UsernamePasswordAuthenticationToken auth =
                new UsernamePasswordAuthenticationToken("principal", "credentials");
        auth.setDetails(null);
        SecurityContextHolder.getContext().setAuthentication(auth);
        final MultipartFile file = new MockMultipartFile("file", "avatar.jpg", "image/jpeg", new byte[]{1});

        final ResponseEntity<ProfileDto> response = controller.uploadAvatar(file, request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        verify(profileService, never()).updateAvatar(any(), any());
    }

    @Test
    void handleAvatarTooLarge_shouldReturn400WithBody() {
        final ResponseEntity<java.util.Map<String, Object>> response =
                controller.handleAvatarTooLarge(new AvatarTooLargeException(3_000_000));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).containsEntry("error", "AVATAR_TOO_LARGE");
    }

    @Test
    void handleMaxUploadSizeExceeded_shouldReturn400WithSameBodyAsTooLarge() {
        final ResponseEntity<java.util.Map<String, Object>> response =
                controller.handleMaxUploadSizeExceeded(new MaxUploadSizeExceededException(3_000_000));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).containsEntry("error", "AVATAR_TOO_LARGE");
    }

    @Test
    void handleInvalidAvatarFormat_shouldReturn400WithBody() {
        final ResponseEntity<java.util.Map<String, Object>> response =
                controller.handleInvalidAvatarFormat(new InvalidAvatarFormatException("application/pdf"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).containsEntry("error", "AVATAR_INVALID_FORMAT");
    }

    // ----------------------------------------------------------------
    // Helpers
    // ----------------------------------------------------------------

    private static User buildUser(final Long userId) {
        final User user = mock(User.class);
        when(user.getId()).thenReturn(userId);
        return user;
    }

    private static void setAuthentication(final User user) {
        final UsernamePasswordAuthenticationToken auth =
                new UsernamePasswordAuthenticationToken(user.getId(), null);
        auth.setDetails(user);
        SecurityContextHolder.getContext().setAuthentication(auth);
    }
}

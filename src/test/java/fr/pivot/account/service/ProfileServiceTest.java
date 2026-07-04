package fr.pivot.account.service;

import fr.pivot.account.dto.ProfileDto;
import fr.pivot.account.dto.ProfileUpdateRequest;
import fr.pivot.account.exception.InvalidProfileNameException;
import fr.pivot.auth.entity.User;
import fr.pivot.auth.repository.UserRepository;
import fr.pivot.tenant.entity.Tenant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests unitaires pour {@link ProfileService} (US02.1.1).
 *
 * <p>Traçabilité :
 * <ul>
 *   <li>AC "GET retourne prénom/nom/email/avatar" — {@code ac0211_01_*}</li>
 *   <li>AC "PATCH met à jour prénom et nom" — {@code ac0211_02_*}</li>
 *   <li>AC "strip HTML côté backend (XSS)" — {@code ac0211_xss_*}</li>
 *   <li>AC "avatar non défini → retourne null" — {@code ac0211_avatarNull_*}</li>
 *   <li>Upload avatar délégué à {@link AvatarStorageService} — {@code ac0211_avatar_*}</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class ProfileServiceTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private AvatarStorageService avatarStorageService;

    private ProfileService profileService;
    private User user;

    @BeforeEach
    void setUp() {
        profileService = new ProfileService(userRepository, avatarStorageService);
        user = new User();
        user.setFirstName("Alice");
        user.setLastName("Martin");
        user.setEmail("alice@pivot.test");
    }

    // ----------------------------------------------------------------
    // getProfile
    // ----------------------------------------------------------------

    @Test
    void ac0211_01_getProfile_returnsFirstNameLastNameEmailAvatar() {
        final ProfileDto dto = profileService.getProfile(user);

        assertThat(dto.firstName()).isEqualTo("Alice");
        assertThat(dto.lastName()).isEqualTo("Martin");
        assertThat(dto.email()).isEqualTo("alice@pivot.test");
    }

    @Test
    void ac0211_avatarNull_getProfile_returnsNullAvatar_whenNoneSet() {
        final ProfileDto dto = profileService.getProfile(user);

        assertThat(dto.avatarUrl()).isNull();
    }

    // ----------------------------------------------------------------
    // updateProfile
    // ----------------------------------------------------------------

    @Test
    void ac0211_02_updateProfile_updatesFirstNameAndLastName() {
        when(userRepository.save(user)).thenReturn(user);

        final ProfileDto dto = profileService.updateProfile(user, new ProfileUpdateRequest("Bob", "Dupont"));

        assertThat(dto.firstName()).isEqualTo("Bob");
        assertThat(dto.lastName()).isEqualTo("Dupont");
        assertThat(user.getFirstName()).isEqualTo("Bob");
        assertThat(user.getLastName()).isEqualTo("Dupont");
        verify(userRepository).save(user);
    }

    @Test
    void ac0211_xss_updateProfile_stripsHtmlTagsFromNames() {
        when(userRepository.save(user)).thenReturn(user);

        final ProfileDto dto = profileService.updateProfile(
                user, new ProfileUpdateRequest("<b>Bob</b>", "<script>alert(1)</script>Dupont"));

        assertThat(dto.firstName()).isEqualTo("Bob");
        assertThat(dto.lastName()).isEqualTo("alert(1)Dupont");
    }

    @Test
    void ac0211_xss_updateProfile_rejectsName_whenBlankAfterStripping() {
        assertThatThrownBy(() -> profileService.updateProfile(
                user, new ProfileUpdateRequest("<script></script>", "Dupont")))
                .isInstanceOf(InvalidProfileNameException.class);

        verify(userRepository, never()).save(any());
    }

    @Test
    void ac0211_02_updateProfile_trimsWhitespace() {
        when(userRepository.save(user)).thenReturn(user);

        profileService.updateProfile(user, new ProfileUpdateRequest("  Bob  ", "  Dupont  "));

        assertThat(user.getFirstName()).isEqualTo("Bob");
        assertThat(user.getLastName()).isEqualTo("Dupont");
    }

    @Test
    void ac0211_err_updateProfile_rejectsMissingFirstName() {
        assertThatThrownBy(() -> profileService.updateProfile(user, new ProfileUpdateRequest(null, "Dupont")))
                .isInstanceOf(InvalidProfileNameException.class);

        verify(userRepository, never()).save(any());
    }

    @Test
    void ac0211_err_updateProfile_rejectsBlankLastName() {
        assertThatThrownBy(() -> profileService.updateProfile(user, new ProfileUpdateRequest("Bob", "   ")))
                .isInstanceOf(InvalidProfileNameException.class);

        verify(userRepository, never()).save(any());
    }

    @Test
    void ac0211_err_updateProfile_rejectsNameOver100Characters() {
        final String tooLong = "A".repeat(101);

        assertThatThrownBy(() -> profileService.updateProfile(user, new ProfileUpdateRequest(tooLong, "Dupont")))
                .isInstanceOf(InvalidProfileNameException.class);

        verify(userRepository, never()).save(any());
    }

    @Test
    void ac0211_02_updateProfile_accepts100CharacterName() {
        final String maxLength = "A".repeat(100);
        when(userRepository.save(user)).thenReturn(user);

        profileService.updateProfile(user, new ProfileUpdateRequest(maxLength, "Dupont"));

        assertThat(user.getFirstName()).isEqualTo(maxLength);
    }

    // ----------------------------------------------------------------
    // updateAvatar
    // ----------------------------------------------------------------

    @Test
    void ac0211_avatar_updateAvatar_delegatesToStorageAndPersistsUrl() {
        final Tenant tenant = mock(Tenant.class);
        when(tenant.getId()).thenReturn(42L);
        user.setTenant(tenant);
        final MultipartFile file = new MockMultipartFile("file", "avatar.jpg", "image/jpeg", new byte[]{1, 2, 3});
        when(avatarStorageService.store(eq(42L), eq(file))).thenReturn("/api/avatars/42/uuid.jpg");
        when(userRepository.save(user)).thenReturn(user);

        final ProfileDto dto = profileService.updateAvatar(user, file);

        assertThat(dto.avatarUrl()).isEqualTo("/api/avatars/42/uuid.jpg");
        assertThat(user.getAvatarUrl()).isEqualTo("/api/avatars/42/uuid.jpg");
        verify(avatarStorageService).deleteIfManaged(null);
    }

    @Test
    void ac0211_avatar_updateAvatar_deletesPreviousAvatar_whenReplaced() {
        final Tenant tenant = mock(Tenant.class);
        when(tenant.getId()).thenReturn(42L);
        user.setTenant(tenant);
        user.setAvatarUrl("/api/avatars/42/old.jpg");
        final MultipartFile file = new MockMultipartFile("file", "avatar.png", "image/png", new byte[]{1, 2, 3});
        when(avatarStorageService.store(eq(42L), eq(file))).thenReturn("/api/avatars/42/new.png");
        when(userRepository.save(user)).thenReturn(user);

        profileService.updateAvatar(user, file);

        verify(avatarStorageService).deleteIfManaged("/api/avatars/42/old.jpg");
    }
}

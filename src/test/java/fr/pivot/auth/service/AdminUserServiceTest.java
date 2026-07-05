package fr.pivot.auth.service;

import fr.pivot.auth.dto.AdminUserDto;
import fr.pivot.auth.dto.AssignableRole;
import fr.pivot.auth.dto.UserStatus;
import fr.pivot.auth.entity.User;
import fr.pivot.auth.exception.AdminUserNotFoundException;
import fr.pivot.auth.exception.InvalidUserFilterException;
import fr.pivot.auth.exception.SelfRoleChangeForbiddenException;
import fr.pivot.auth.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Tests unitaires pour {@link AdminUserService} — US06.1.1.
 *
 * <p>Vérifie le plafonnement de pagination, la traduction du filtre {@code status} et le
 * mapping DTO. La vérification {@code @PreAuthorize("hasRole('ADMIN')")} n'est pas exercée
 * ici (pas de proxy Spring AOP dans un test Mockito pur) — couverte par
 * {@code AdminUserIntegrationTest} (contexte Spring réel avec {@code @EnableMethodSecurity}).
 * La correction fonctionnelle des filtres (role/status/search combinés en base) n'est pas
 * non plus testable ici (les {@link Specification} JPA ne s'évaluent que face à un
 * {@code EntityManager} réel) — couverte par les tests d'intégration.
 */
@ExtendWith(MockitoExtension.class)
class AdminUserServiceTest {

    private static final Long TENANT_ID = 42L;

    @Mock
    private UserRepository userRepository;

    @Mock
    private TokenService tokenService;

    private AdminUserService service;

    @BeforeEach
    void setUp() {
        service = new AdminUserService(userRepository, tokenService);
    }

    // ----------------------------------------------------------------
    // AC06.1.1-01 : pagination par défaut / clampée
    // ----------------------------------------------------------------

    @Test
    void ac0611_01_usesDefaultPageSize_whenSizeNotProvided() {
        stubEmptyPage();

        service.listUsers(TENANT_ID, 0, 0, null, null, null);

        final Pageable pageable = capturePageable();
        assertThat(pageable.getPageSize()).isEqualTo(AdminUserService.DEFAULT_PAGE_SIZE);
        assertThat(pageable.getPageNumber()).isZero();
    }

    @Test
    void ac0611_02_clampsPageSize_whenAboveMax100() {
        stubEmptyPage();

        service.listUsers(TENANT_ID, 0, 500, null, null, null);

        assertThat(capturePageable().getPageSize()).isEqualTo(AdminUserService.MAX_PAGE_SIZE);
    }

    @Test
    void ac0611_03_usesDefaultPageSize_whenSizeIsNegative() {
        stubEmptyPage();

        service.listUsers(TENANT_ID, 0, -5, null, null, null);

        assertThat(capturePageable().getPageSize()).isEqualTo(AdminUserService.DEFAULT_PAGE_SIZE);
    }

    @Test
    void ac0611_04_clampsPageNumber_whenNegative() {
        stubEmptyPage();

        service.listUsers(TENANT_ID, -3, 20, null, null, null);

        assertThat(capturePageable().getPageNumber()).isZero();
    }

    @Test
    void ac0611_05_respectsExplicitValidPageAndSize() {
        stubEmptyPage();

        service.listUsers(TENANT_ID, 2, 50, null, null, null);

        final Pageable pageable = capturePageable();
        assertThat(pageable.getPageNumber()).isEqualTo(2);
        assertThat(pageable.getPageSize()).isEqualTo(50);
    }

    // ----------------------------------------------------------------
    // AC06.1.1-SEC-01 : filtre status invalide
    // ----------------------------------------------------------------

    @Test
    void ac0611Sec01_throwsInvalidUserFilter_whenStatusUnknown() {
        assertThatThrownBy(() -> service.listUsers(TENANT_ID, 0, 20, null, "bogus", null))
                .isInstanceOf(InvalidUserFilterException.class)
                .hasFieldOrPropertyWithValue("field", "status")
                .hasFieldOrPropertyWithValue("value", "bogus");

        verify(userRepository, never()).findAll(any(Specification.class), any(Pageable.class));
    }

    @Test
    void ac0611_06_ignoresStatusFilter_whenBlank() {
        stubEmptyPage();

        service.listUsers(TENANT_ID, 0, 20, null, "  ", null);

        verify(userRepository).findAll(any(Specification.class), any(Pageable.class));
    }

    @Test
    void ac0611_07_acceptsStatusFilter_caseInsensitive() {
        stubEmptyPage();

        service.listUsers(TENANT_ID, 0, 20, null, "active", null);

        verify(userRepository).findAll(any(Specification.class), any(Pageable.class));
    }

    // ----------------------------------------------------------------
    // AC06.1.1-SEC-03 : filtre role invalide (symétrique du filtre status)
    // ----------------------------------------------------------------

    @Test
    void ac0611Sec02_throwsInvalidUserFilter_whenRoleUnknown() {
        assertThatThrownBy(() -> service.listUsers(TENANT_ID, 0, 20, "ROLE_BOGUS", null, null))
                .isInstanceOf(InvalidUserFilterException.class)
                .hasFieldOrPropertyWithValue("field", "role")
                .hasFieldOrPropertyWithValue("value", "ROLE_BOGUS");

        verify(userRepository, never()).findAll(any(Specification.class), any(Pageable.class));
    }

    @Test
    void ac0611_09_ignoresRoleFilter_whenBlank() {
        stubEmptyPage();

        service.listUsers(TENANT_ID, 0, 20, "  ", null, null);

        verify(userRepository).findAll(any(Specification.class), any(Pageable.class));
    }

    @Test
    void ac0611_10_acceptsRoleFilter_whenKnownRole() {
        stubEmptyPage();

        service.listUsers(TENANT_ID, 0, 20, "ROLE_ADMIN", null, null);

        verify(userRepository).findAll(any(Specification.class), any(Pageable.class));
    }

    // ----------------------------------------------------------------
    // AC06.1.1-02 : mapping DTO — jamais l'entité JPA exposée
    // ----------------------------------------------------------------

    @Test
    void ac0611_08_mapsUserEntityToAdminUserDto() {
        final User user = mock(User.class);
        when(user.getId()).thenReturn(7L);
        when(user.getEmail()).thenReturn("alice@pivot.test");
        when(user.getFirstName()).thenReturn("Alice");
        when(user.getLastName()).thenReturn("Martin");
        when(user.getRole()).thenReturn("ROLE_USER");
        when(user.isActive()).thenReturn(true);
        when(user.isBlocked()).thenReturn(false);
        final Instant createdAt = Instant.parse("2026-01-01T00:00:00Z");
        when(user.getCreatedAt()).thenReturn(createdAt);

        when(userRepository.findAll(any(Specification.class), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(user)));

        final Page<AdminUserDto> result = service.listUsers(TENANT_ID, 0, 20, null, null, null);

        assertThat(result.getContent()).hasSize(1);
        final AdminUserDto dto = result.getContent().get(0);
        assertThat(dto.id()).isEqualTo(7L);
        assertThat(dto.email()).isEqualTo("alice@pivot.test");
        assertThat(dto.firstName()).isEqualTo("Alice");
        assertThat(dto.lastName()).isEqualTo("Martin");
        assertThat(dto.role()).isEqualTo("ROLE_USER");
        assertThat(dto.status()).isEqualTo(UserStatus.ACTIVE);
        assertThat(dto.createdAt()).isEqualTo(createdAt);
    }

    // ----------------------------------------------------------------
    // AC06.1.3 : modification de rôle
    // ----------------------------------------------------------------

    @Test
    void ac0613_01_updatesRoleAndRevokesAllTokens_whenTargetBelongsToTenant() {
        final User target = mock(User.class);
        when(target.getId()).thenReturn(99L);
        when(target.getEmail()).thenReturn("bob@pivot.test");
        when(target.getFirstName()).thenReturn("Bob");
        when(target.getLastName()).thenReturn("Dupont");
        when(target.getRole()).thenReturn("ROLE_ADMIN");
        when(target.isActive()).thenReturn(true);
        when(target.isBlocked()).thenReturn(false);
        when(target.getCreatedAt()).thenReturn(Instant.parse("2026-01-01T00:00:00Z"));
        when(userRepository.findByIdAndTenantIdAndDeletedAtIsNull(99L, TENANT_ID))
                .thenReturn(Optional.of(target));

        final AdminUserDto dto = service.updateRole(TENANT_ID, 1L, 99L, AssignableRole.ROLE_ADMIN);

        verify(target).setRole("ROLE_ADMIN");
        verify(userRepository).save(target);
        verify(tokenService).revokeAllForUser(99L);
        assertThat(dto.id()).isEqualTo(99L);
        assertThat(dto.role()).isEqualTo("ROLE_ADMIN");
    }

    @Test
    void ac0613Sec01_throwsSelfRoleChangeForbidden_whenTargetIsCaller() {
        assertThatThrownBy(() -> service.updateRole(TENANT_ID, 1L, 1L, AssignableRole.ROLE_USER))
                .isInstanceOf(SelfRoleChangeForbiddenException.class);

        verifyNoInteractions(userRepository, tokenService);
    }

    @Test
    void ac0613Sec02_throwsAdminUserNotFound_whenTargetNotInTenant() {
        when(userRepository.findByIdAndTenantIdAndDeletedAtIsNull(123L, TENANT_ID))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.updateRole(TENANT_ID, 1L, 123L, AssignableRole.ROLE_USER))
                .isInstanceOf(AdminUserNotFoundException.class);

        verify(tokenService, never()).revokeAllForUser(any());
    }

    // ----------------------------------------------------------------
    // Helpers
    // ----------------------------------------------------------------

    @SuppressWarnings("unchecked")
    private void stubEmptyPage() {
        when(userRepository.findAll(any(Specification.class), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of()));
    }

    @SuppressWarnings("unchecked")
    private Pageable capturePageable() {
        final ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
        verify(userRepository).findAll(any(Specification.class), captor.capture());
        return captor.getValue();
    }
}

package fr.pivot.auth.controller;

import fr.pivot.AbstractIntegrationTest;
import fr.pivot.auth.dto.AdminUserDto;
import fr.pivot.auth.dto.UserStatus;
import fr.pivot.auth.entity.User;
import fr.pivot.auth.exception.InvalidUserFilterException;
import fr.pivot.auth.repository.UserRepository;
import fr.pivot.auth.service.AdminUserService;
import fr.pivot.tenant.entity.Tenant;
import fr.pivot.tenant.repository.TenantRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests d'intégration (PostgreSQL via Testcontainers, contexte Spring réel) pour
 * {@link AdminUserService} / {@link AdminUserController} — US06.1.1.
 *
 * <p>Traçabilité :
 * <ul>
 *   <li>AC — {@code GET /api/admin/users} retourne la liste paginée du tenant courant ;</li>
 *   <li>AC — champs {@code id, email, firstName, lastName, role, status, createdAt} ;</li>
 *   <li>AC — filtres {@code role}, {@code status}, {@code search} ;</li>
 *   <li>AC — pagination {@code page}/{@code size}, défaut 20, max 100 ;</li>
 *   <li>Security — {@code @PreAuthorize("hasRole('ADMIN')")} effectivement évalué par le proxy
 *       Spring Method Security : un porteur {@code ROLE_USER} est rejeté ;</li>
 *   <li>Security — isolation tenant obligatoire : un admin ne voit jamais les utilisateurs
 *       d'un autre tenant, même si le tenant a le même nombre/ordre d'utilisateurs.</li>
 * </ul>
 */
class AdminUserIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private AdminUserService adminUserService;

    @Autowired
    private AdminUserController adminUserController;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private TenantRepository tenantRepository;

    private Long tenantAId;
    private Long tenantBId;

    @BeforeEach
    void setUp() {
        tenantAId = createTenant("admin-users-it-tenant-a");
        tenantBId = createTenant("admin-users-it-tenant-b");
    }

    @AfterEach
    void tearDown() {
        final Specification<User> ofTestTenants =
                (root, query, cb) -> root.get("tenant").get("id").in(tenantAId, tenantBId);
        userRepository.deleteAll(userRepository.findAll(ofTestTenants));
        tenantRepository.deleteById(tenantAId);
        tenantRepository.deleteById(tenantBId);
        SecurityContextHolder.clearContext();
    }

    // ----------------------------------------------------------------
    // AC : liste paginée du tenant courant + champs attendus
    // ----------------------------------------------------------------

    @Test
    void ac0611_01_returnsOnlyUsersOfCallerTenant_withExpectedFields() {
        final User user = createUser(tenantAId, "alice@tenant-a.test", "Alice", "Martin",
                "ROLE_USER", true, false);

        setAuthentication("ROLE_ADMIN");
        final Page<AdminUserDto> page = adminUserService.listUsers(tenantAId, 0, 20, null, null, null);

        assertThat(page.getTotalElements()).isEqualTo(1);
        final AdminUserDto dto = page.getContent().get(0);
        assertThat(dto.id()).isEqualTo(user.getId());
        assertThat(dto.email()).isEqualTo("alice@tenant-a.test");
        assertThat(dto.firstName()).isEqualTo("Alice");
        assertThat(dto.lastName()).isEqualTo("Martin");
        assertThat(dto.role()).isEqualTo("ROLE_USER");
        assertThat(dto.status()).isEqualTo(UserStatus.ACTIVE);
        assertThat(dto.createdAt()).isNotNull();
    }

    @Test
    void ac0611_02_respondsWithSpringPageShape_viaController() {
        createUser(tenantAId, "bob@tenant-a.test", "Bob", "Dupont", "ROLE_USER", true, false);
        setAuthenticationWithUserDetails("ROLE_ADMIN", tenantAId);

        final ResponseEntity<Page<AdminUserDto>> response = adminUserController.list(0, 20, null, null, null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getContent()).hasSize(1);
        assertThat(response.getBody().getTotalElements()).isEqualTo(1);
        assertThat(response.getBody().getNumber()).isZero();
        assertThat(response.getBody().getSize()).isEqualTo(20);
    }

    // ----------------------------------------------------------------
    // Security : RBAC porté par le service
    // ----------------------------------------------------------------

    @Test
    void ac0611Sec01_throwsAccessDenied_whenCallerIsRoleUser() {
        setAuthentication("ROLE_USER");

        assertThatThrownBy(() -> adminUserService.listUsers(tenantAId, 0, 20, null, null, null))
                .isInstanceOf(AccessDeniedException.class);
    }

    // ----------------------------------------------------------------
    // Security : isolation tenant obligatoire
    // ----------------------------------------------------------------

    @Test
    void ac0611Sec02_neverReturnsOtherTenantUsers_crossTenantIsolation() {
        createUser(tenantAId, "a1@tenant-a.test", "A1", "Tenant", "ROLE_USER", true, false);
        createUser(tenantAId, "a2@tenant-a.test", "A2", "Tenant", "ROLE_USER", true, false);
        createUser(tenantBId, "b1@tenant-b.test", "B1", "Tenant", "ROLE_USER", true, false);

        setAuthentication("ROLE_ADMIN");
        final Page<AdminUserDto> pageA = adminUserService.listUsers(tenantAId, 0, 20, null, null, null);
        final Page<AdminUserDto> pageB = adminUserService.listUsers(tenantBId, 0, 20, null, null, null);

        assertThat(pageA.getTotalElements()).isEqualTo(2);
        assertThat(pageA.getContent()).extracting(AdminUserDto::email)
                .containsExactlyInAnyOrder("a1@tenant-a.test", "a2@tenant-a.test");
        assertThat(pageB.getTotalElements()).isEqualTo(1);
        assertThat(pageB.getContent()).extracting(AdminUserDto::email)
                .containsExactly("b1@tenant-b.test");
    }

    // ----------------------------------------------------------------
    // AC : pagination — défaut 20, bornes, plafond 100
    // ----------------------------------------------------------------

    @Test
    void ac0611_03_paginatesWithDefaultPageSize20() {
        for (int i = 0; i < 25; i++) {
            createUser(tenantAId, "user" + i + "@tenant-a.test", "User", "N" + i, "ROLE_USER", true, false);
        }
        setAuthentication("ROLE_ADMIN");

        final Page<AdminUserDto> firstPage = adminUserService.listUsers(tenantAId, 0, 0, null, null, null);
        final Page<AdminUserDto> secondPage = adminUserService.listUsers(tenantAId, 1, 0, null, null, null);

        assertThat(firstPage.getSize()).isEqualTo(AdminUserService.DEFAULT_PAGE_SIZE);
        assertThat(firstPage.getContent()).hasSize(20);
        assertThat(firstPage.getTotalElements()).isEqualTo(25);
        assertThat(firstPage.getTotalPages()).isEqualTo(2);
        assertThat(secondPage.getContent()).hasSize(5);
    }

    @Test
    void ac0611_04_clampsPageSizeAboveMaxTo100() {
        for (int i = 0; i < 3; i++) {
            createUser(tenantAId, "clamp" + i + "@tenant-a.test", "Clamp", "N" + i, "ROLE_USER", true, false);
        }
        setAuthentication("ROLE_ADMIN");

        final Page<AdminUserDto> page = adminUserService.listUsers(tenantAId, 0, 5000, null, null, null);

        assertThat(page.getSize()).isEqualTo(AdminUserService.MAX_PAGE_SIZE);
        assertThat(page.getContent()).hasSize(3);
    }

    // ----------------------------------------------------------------
    // AC : filtre role
    // ----------------------------------------------------------------

    @Test
    void ac0611_05_filtersByRole() {
        createUser(tenantAId, "admin@tenant-a.test", "Admin", "One", "ROLE_ADMIN", true, false);
        createUser(tenantAId, "user@tenant-a.test", "User", "One", "ROLE_USER", true, false);
        setAuthentication("ROLE_ADMIN");

        final Page<AdminUserDto> page = adminUserService.listUsers(tenantAId, 0, 20, "ROLE_ADMIN", null, null);

        assertThat(page.getContent()).extracting(AdminUserDto::email).containsExactly("admin@tenant-a.test");
    }

    // ----------------------------------------------------------------
    // AC : filtre status
    // ----------------------------------------------------------------

    @Test
    void ac0611_06_filtersByStatusActive() {
        createUser(tenantAId, "active@tenant-a.test", "Active", "One", "ROLE_USER", true, false);
        createUser(tenantAId, "inactive@tenant-a.test", "Inactive", "One", "ROLE_USER", false, false);
        createUser(tenantAId, "blocked@tenant-a.test", "Blocked", "One", "ROLE_USER", true, true);
        setAuthentication("ROLE_ADMIN");

        final Page<AdminUserDto> page = adminUserService.listUsers(tenantAId, 0, 20, null, "ACTIVE", null);

        assertThat(page.getContent()).extracting(AdminUserDto::email).containsExactly("active@tenant-a.test");
    }

    @Test
    void ac0611_07_filtersByStatusInactive() {
        createUser(tenantAId, "active@tenant-a.test", "Active", "One", "ROLE_USER", true, false);
        createUser(tenantAId, "inactive@tenant-a.test", "Inactive", "One", "ROLE_USER", false, false);
        setAuthentication("ROLE_ADMIN");

        final Page<AdminUserDto> page = adminUserService.listUsers(tenantAId, 0, 20, null, "inactive", null);

        assertThat(page.getContent()).extracting(AdminUserDto::email).containsExactly("inactive@tenant-a.test");
    }

    @Test
    void ac0611_08_filtersByStatusBlocked() {
        createUser(tenantAId, "active@tenant-a.test", "Active", "One", "ROLE_USER", true, false);
        createUser(tenantAId, "blocked@tenant-a.test", "Blocked", "One", "ROLE_USER", true, true);
        setAuthentication("ROLE_ADMIN");

        final Page<AdminUserDto> page = adminUserService.listUsers(tenantAId, 0, 20, null, "BLOCKED", null);

        assertThat(page.getContent()).extracting(AdminUserDto::email).containsExactly("blocked@tenant-a.test");
    }

    @Test
    void ac0611Err01_throwsInvalidUserFilter_whenStatusUnknown() {
        setAuthentication("ROLE_ADMIN");

        assertThatThrownBy(() -> adminUserService.listUsers(tenantAId, 0, 20, null, "not-a-status", null))
                .isInstanceOf(InvalidUserFilterException.class);
    }

    // ----------------------------------------------------------------
    // AC : filtre search (email ou nom)
    // ----------------------------------------------------------------

    @Test
    void ac0611_09_filtersBySearchMatchingEmail() {
        createUser(tenantAId, "findme@tenant-a.test", "Zack", "Zebra", "ROLE_USER", true, false);
        createUser(tenantAId, "other@tenant-a.test", "Other", "Person", "ROLE_USER", true, false);
        setAuthentication("ROLE_ADMIN");

        final Page<AdminUserDto> page = adminUserService.listUsers(tenantAId, 0, 20, null, null, "findme");

        assertThat(page.getContent()).extracting(AdminUserDto::email).containsExactly("findme@tenant-a.test");
    }

    @Test
    void ac0611_10_filtersBySearchMatchingNameCaseInsensitive() {
        createUser(tenantAId, "someone@tenant-a.test", "Isabelle", "Durand", "ROLE_USER", true, false);
        createUser(tenantAId, "other@tenant-a.test", "Other", "Person", "ROLE_USER", true, false);
        setAuthentication("ROLE_ADMIN");

        final Page<AdminUserDto> page = adminUserService.listUsers(tenantAId, 0, 20, null, null, "ISABELLE");

        assertThat(page.getContent()).extracting(AdminUserDto::email).containsExactly("someone@tenant-a.test");
    }

    // ----------------------------------------------------------------
    // AC implicite : comptes supprimés (soft delete) jamais listés
    // ----------------------------------------------------------------

    @Test
    void ac0611_11_excludesSoftDeletedUsers() {
        final User deleted = createUser(tenantAId, "deleted@tenant-a.test", "Deleted", "One", "ROLE_USER", true, false);
        deleted.setDeletedAt(Instant.now());
        userRepository.save(deleted);
        createUser(tenantAId, "kept@tenant-a.test", "Kept", "One", "ROLE_USER", true, false);
        setAuthentication("ROLE_ADMIN");

        final Page<AdminUserDto> page = adminUserService.listUsers(tenantAId, 0, 20, null, null, null);

        assertThat(page.getContent()).extracting(AdminUserDto::email).containsExactly("kept@tenant-a.test");
    }

    // ----------------------------------------------------------------
    // Helpers
    // ----------------------------------------------------------------

    private Long createTenant(final String slugPrefix) {
        final Tenant tenant = new Tenant();
        tenant.setSlug(slugPrefix + "-" + System.nanoTime());
        tenant.setName(slugPrefix);
        return tenantRepository.save(tenant).getId();
    }

    private User createUser(
            final Long tenantId,
            final String email,
            final String firstName,
            final String lastName,
            final String role,
            final boolean active,
            final boolean blocked) {
        final Tenant tenant = tenantRepository.findById(tenantId).orElseThrow();
        final User user = new User();
        user.setTenant(tenant);
        user.setEmail(email);
        user.setFirstName(firstName);
        user.setLastName(lastName);
        user.setRole(role);
        user.setActive(active);
        user.setBlocked(blocked);
        return userRepository.save(user);
    }

    private static void setAuthentication(final String role) {
        final UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken(
                "test-principal", null, List.of(new SimpleGrantedAuthority(role)));
        SecurityContextHolder.getContext().setAuthentication(auth);
    }

    /**
     * Comme {@link #setAuthentication(String)}, mais pose aussi un {@link User} réel dans les
     * détails de l'authentification — requis par {@code AdminUserController.resolveAdmin()}.
     */
    private void setAuthenticationWithUserDetails(final String role, final Long tenantId) {
        final Tenant tenant = tenantRepository.findById(tenantId).orElseThrow();
        final User user = new User();
        user.setTenant(tenant);

        final UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken(
                "test-principal", null, List.of(new SimpleGrantedAuthority(role)));
        auth.setDetails(user);
        SecurityContextHolder.getContext().setAuthentication(auth);
    }
}

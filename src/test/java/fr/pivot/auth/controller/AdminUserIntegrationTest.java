package fr.pivot.auth.controller;

import fr.pivot.AbstractIntegrationTest;
import fr.pivot.auth.dto.AdminUserDto;
import fr.pivot.auth.dto.AssignableRole;
import fr.pivot.auth.dto.AssignableStatus;
import fr.pivot.auth.dto.UserStatus;
import fr.pivot.auth.entity.AccessToken;
import fr.pivot.auth.entity.AuditEvent;
import fr.pivot.auth.entity.AuthMethod;
import fr.pivot.auth.entity.TokenStatus;
import fr.pivot.auth.entity.User;
import fr.pivot.auth.exception.AdminUserNotFoundException;
import fr.pivot.auth.exception.InvalidUserFilterException;
import fr.pivot.auth.exception.SelfRoleChangeForbiddenException;
import fr.pivot.auth.exception.SelfStatusChangeForbiddenException;
import fr.pivot.auth.exception.SuperAdminRoleChangeForbiddenException;
import fr.pivot.auth.repository.AccessTokenRepository;
import fr.pivot.auth.repository.AuditEventRepository;
import fr.pivot.auth.repository.UserRepository;
import fr.pivot.auth.service.AdminUserService;
import fr.pivot.auth.service.AuditService;
import fr.pivot.auth.service.TokenService;
import fr.pivot.tenant.entity.Tenant;
import fr.pivot.tenant.repository.TenantRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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

    @Autowired
    private TokenService tokenService;

    @Autowired
    private AccessTokenRepository accessTokenRepository;

    @Autowired
    private AuditEventRepository auditEventRepository;

    @Autowired
    private WebApplicationContext webApplicationContext;

    private Long tenantAId;
    private Long tenantBId;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        tenantAId = createTenant("admin-users-it-tenant-a");
        tenantBId = createTenant("admin-users-it-tenant-b");
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext)
                .apply(springSecurity())
                .build();
    }

    @AfterEach
    void tearDown() {
        final Specification<User> ofTestTenants =
                (root, query, cb) -> root.get("tenant").get("id").in(tenantAId, tenantBId);
        final List<User> testUsers = userRepository.findAll(ofTestTenants);
        // audit_events.user_id has no ON DELETE CASCADE (unlike access_tokens) — the audit
        // trail of a deleted user is intentionally kept in production; test cleanup must
        // therefore purge it explicitly before deleting the user row itself (FK violation
        // otherwise), for the new PATCH .../role tests which log UserRoleChanged events.
        testUsers.forEach(u -> auditEventRepository.deleteAll(
                auditEventRepository.findByUserIdOrderByCreatedAtDesc(u.getId())));
        userRepository.deleteAll(testUsers);
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

    @Test
    void ac0611Err02_throwsInvalidUserFilter_whenRoleUnknown() {
        // Symétrique de ac0611Err01 : un rôle inconnu doit échouer explicitement (400 au
        // niveau contrôleur) plutôt que de retourner silencieusement une page vide.
        setAuthentication("ROLE_ADMIN");

        assertThatThrownBy(() -> adminUserService.listUsers(tenantAId, 0, 20, "ROLE_BOGUS", null, null))
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
    // US06.1.3 : modification de rôle — service (BDD réelle)
    // ----------------------------------------------------------------

    @Test
    void ac0613_01_updatesRole_persistsChange_andReturnsUpdatedDto() {
        final User admin = createUser(tenantAId, "admin1@tenant-a.test", "Admin", "One", "ROLE_ADMIN", true, false);
        final User target = createUser(tenantAId, "target1@tenant-a.test", "Target", "One", "ROLE_USER", true, false);
        setAuthentication("ROLE_ADMIN");

        final AdminUserDto dto =
                adminUserService.updateRole(tenantAId, admin.getId(), target.getId(), AssignableRole.ROLE_ADMIN);

        assertThat(dto.id()).isEqualTo(target.getId());
        assertThat(dto.role()).isEqualTo("ROLE_ADMIN");
        assertThat(userRepository.findById(target.getId()).orElseThrow().getRole()).isEqualTo("ROLE_ADMIN");
    }

    @Test
    void ac0613Sec01_throwsSelfRoleChangeForbidden_whenAdminTargetsOwnId() {
        final User admin = createUser(tenantAId, "admin2@tenant-a.test", "Admin", "Two", "ROLE_ADMIN", true, false);
        setAuthentication("ROLE_ADMIN");

        assertThatThrownBy(() ->
                adminUserService.updateRole(tenantAId, admin.getId(), admin.getId(), AssignableRole.ROLE_USER))
                .isInstanceOf(SelfRoleChangeForbiddenException.class);

        assertThat(userRepository.findById(admin.getId()).orElseThrow().getRole()).isEqualTo("ROLE_ADMIN");
    }

    @Test
    void ac0613Sec02_throwsAdminUserNotFound_whenTargetBelongsToAnotherTenant() {
        final User admin = createUser(tenantAId, "admin3@tenant-a.test", "Admin", "Three", "ROLE_ADMIN", true, false);
        final User targetInB = createUser(tenantBId, "target-b@tenant-b.test", "Target", "B", "ROLE_USER", true, false);
        setAuthentication("ROLE_ADMIN");

        assertThatThrownBy(() ->
                adminUserService.updateRole(tenantAId, admin.getId(), targetInB.getId(), AssignableRole.ROLE_ADMIN))
                .isInstanceOf(AdminUserNotFoundException.class);

        // Isolation tenant : la ressource d'un autre tenant reste intacte, jamais modifiée.
        assertThat(userRepository.findById(targetInB.getId()).orElseThrow().getRole()).isEqualTo("ROLE_USER");
    }

    @Test
    void ac0613Sec03_throwsAdminUserNotFound_whenTargetDoesNotExist() {
        final User admin = createUser(tenantAId, "admin4@tenant-a.test", "Admin", "Four", "ROLE_ADMIN", true, false);
        setAuthentication("ROLE_ADMIN");

        assertThatThrownBy(() ->
                adminUserService.updateRole(tenantAId, admin.getId(), 999_999_999L, AssignableRole.ROLE_ADMIN))
                .isInstanceOf(AdminUserNotFoundException.class);
    }

    @Test
    void ac0613Sec04_throwsAccessDenied_whenCallerIsRoleUser() {
        final User target = createUser(tenantAId, "victim@tenant-a.test", "Victim", "One", "ROLE_USER", true, false);
        setAuthentication("ROLE_USER");

        assertThatThrownBy(() ->
                adminUserService.updateRole(tenantAId, 999L, target.getId(), AssignableRole.ROLE_ADMIN))
                .isInstanceOf(AccessDeniedException.class);
    }

    /**
     * Sécurité — {@code ROLE_SUPER_ADMIN} est un rôle plateforme qui peut cohabiter, en base,
     * avec des comptes {@code ROLE_ADMIN} dans le même tenant (le « tenant système », voir seed
     * {@code super_admin@pivot.test}/{@code admin@pivot.test} sur {@code tenant_id=1} en
     * production). Sans cette garde, un simple {@code ROLE_ADMIN} de ce tenant pourrait
     * rétrograder un super-admin en {@code ROLE_USER} par ce endpoint tenant.
     */
    @Test
    void ac0613Sec05_throwsSuperAdminRoleChangeForbidden_whenTargetIsSuperAdminInSameTenant() {
        final User admin = createUser(tenantAId, "admin5@tenant-a.test", "Admin", "Five", "ROLE_ADMIN", true, false);
        final User superAdmin =
                createUser(tenantAId, "super5@tenant-a.test", "Super", "Five", "ROLE_SUPER_ADMIN", true, false);
        setAuthentication("ROLE_ADMIN");

        assertThatThrownBy(() ->
                adminUserService.updateRole(tenantAId, admin.getId(), superAdmin.getId(), AssignableRole.ROLE_USER))
                .isInstanceOf(SuperAdminRoleChangeForbiddenException.class);

        assertThat(userRepository.findById(superAdmin.getId()).orElseThrow().getRole())
                .isEqualTo("ROLE_SUPER_ADMIN");
    }

    // ----------------------------------------------------------------
    // US06.1.3 : bout-en-bout HTTP (token réel, filtre de sécurité réel)
    // ----------------------------------------------------------------

    @Test
    void ac0613Http01_returns200_andPersistsRole_whenCallerIsAdminOfSameTenant() throws Exception {
        final User adminA = createUser(tenantAId, "http-admin1@tenant-a.test", "Admin", "A", "ROLE_ADMIN", true, false);
        final User target = createUser(tenantAId, "http-target1@tenant-a.test", "Target", "A", "ROLE_USER", true, false);
        final String adminToken = issueToken(adminA);

        mockMvc.perform(patch("/api/admin/users/{userId}/role", target.getId())
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"role\":\"ROLE_ADMIN\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(target.getId()))
                .andExpect(jsonPath("$.role").value("ROLE_ADMIN"));

        assertThat(userRepository.findById(target.getId()).orElseThrow().getRole()).isEqualTo("ROLE_ADMIN");
    }

    @Test
    void ac0613Http02_returns404_whenUserIdBelongsToAnotherTenant_crossTenantIsolation() throws Exception {
        final User adminA = createUser(tenantAId, "http-admin2@tenant-a.test", "Admin", "A", "ROLE_ADMIN", true, false);
        final User targetB = createUser(tenantBId, "http-target-b@tenant-b.test", "Target", "B", "ROLE_USER", true, false);
        final String adminToken = issueToken(adminA);

        mockMvc.perform(patch("/api/admin/users/{userId}/role", targetB.getId())
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"role\":\"ROLE_ADMIN\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("USER_NOT_FOUND"));

        assertThat(userRepository.findById(targetB.getId()).orElseThrow().getRole()).isEqualTo("ROLE_USER");
    }

    @Test
    void ac0613Http03_returns403_whenAdminTargetsOwnRole_selfDemotionForbidden() throws Exception {
        final User adminA = createUser(tenantAId, "http-self-admin@tenant-a.test", "Self", "Admin", "ROLE_ADMIN", true, false);
        final String adminToken = issueToken(adminA);

        mockMvc.perform(patch("/api/admin/users/{userId}/role", adminA.getId())
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"role\":\"ROLE_USER\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("SELF_ROLE_CHANGE_FORBIDDEN"));

        assertThat(userRepository.findById(adminA.getId()).orElseThrow().getRole()).isEqualTo("ROLE_ADMIN");
    }

    @Test
    void ac0613Http04_logsUserRoleChangedAuditEvent_onSuccessfulChange() throws Exception {
        final User adminA = createUser(tenantAId, "http-audit-admin@tenant-a.test", "Admin", "Au", "ROLE_ADMIN", true, false);
        final User target = createUser(tenantAId, "http-audit-target@tenant-a.test", "Target", "Au", "ROLE_USER", true, false);
        final String adminToken = issueToken(adminA);

        mockMvc.perform(patch("/api/admin/users/{userId}/role", target.getId())
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"role\":\"ROLE_ADMIN\"}"))
                .andExpect(status().isOk());

        final List<AuditEvent> events = auditEventRepository.findByUserIdOrderByCreatedAtDesc(adminA.getId());
        assertThat(events).anySatisfy(e -> assertThat(e.getEventType()).isEqualTo(AuditService.USER_ROLE_CHANGED));
    }

    /**
     * AC — « Après modification de rôle, tous les tokens actifs de l'utilisateur concerné sont
     * révoqués immédiatement (...) Test TI valide qu'un appel admin avec l'ancien token retourne
     * 401 dans les 100ms suivant la révocation ».
     *
     * <p><strong>Écart assumé avec le texte de l'AC :</strong> ce backend ne configure aucun
     * {@code AuthenticationEntryPoint} personnalisé (voir {@code SecurityConfig}) — le
     * comportement par défaut de Spring Security pour toute requête non authentifiée (token
     * absent, invalide, expiré ou révoqué) est {@code 403 Forbidden}, jamais {@code 401}. C'est
     * la convention déjà établie et documentée sur chaque endpoint authentifié de cette
     * application (voir {@code SessionControllerIntegrationTest#list_returns403_whenNoBearerToken}).
     * Introduire un {@code 401} spécifique à cet unique endpoint casserait cette cohérence
     * transversale pour un gain nul côté client (Angular traite déjà {@code 401}/{@code 403}
     * de façon identique : déconnexion + redirection login). L'intention de l'AC — la révocation
     * est immédiate et rend l'ancien token inutilisable — est donc vérifiée ici par la
     * combinaison : (a) la requête HTTP avec l'ancien token échoue ({@code 403}, cohérent avec le
     * reste de l'API) et (b) une assertion BDD directe prouve que l'échec est bien dû à une ligne
     * {@code REVOKED} (révocation réelle), pas à un hasard de résolution de rôle. Signalé au
     * mainteneur dans la PR pour arbitrage (introduire un {@code AuthenticationEntryPoint} global
     * 401 serait un changement transversal hors périmètre de cette US).
     *
     * <p>La cible est initialement {@code ROLE_ADMIN} et rétrogradée en {@code ROLE_USER} —
     * précisément pour que ce test ne puisse pas être confondu avec une simple perte de droit
     * (qui donnerait aussi {@code 403} si le token n'était que "réévalué" sans être révoqué) :
     * seule l'assertion BDD tranche. Aucun minuteur n'est nécessaire : l'appel avec l'ancien
     * token a lieu immédiatement après la réponse {@code 200} du PATCH, dans le même thread de
     * test — largement sous la barre des 100ms évoquée par l'AC.
     */
    @Test
    void ac0613Http05_oldTokenRevoked_rejectedImmediatelyAfterRoleChange() throws Exception {
        final User adminA = createUser(tenantAId, "http-revoke-admin@tenant-a.test", "Admin", "R", "ROLE_ADMIN", true, false);
        final User targetAdmin =
                createUser(tenantAId, "http-revoke-target@tenant-a.test", "Target", "R", "ROLE_ADMIN", true, false);
        final String adminToken = issueToken(adminA);
        final String targetOldToken = issueToken(targetAdmin);

        // Sanity check: the target's token is valid for an admin-only endpoint before the change.
        mockMvc.perform(get("/api/admin/users").header("Authorization", "Bearer " + targetOldToken))
                .andExpect(status().isOk());

        mockMvc.perform(patch("/api/admin/users/{userId}/role", targetAdmin.getId())
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"role\":\"ROLE_USER\"}"))
                .andExpect(status().isOk());

        // Rejected immediately — see JavaDoc above for why 403 (not 401) is the correct/expected
        // status in this codebase.
        mockMvc.perform(get("/api/admin/users").header("Authorization", "Bearer " + targetOldToken))
                .andExpect(status().isForbidden());

        // Proves the 403 above is caused by an actual revoked row (not a role/authorization
        // side effect) — the definitive check for "tokens are revoked", independent of HTTP
        // status code conventions.
        final List<AccessToken> tokens = accessTokenRepository.findByUserIdOrderByCreatedAtDesc(targetAdmin.getId());
        assertThat(tokens).hasSize(1);
        assertThat(tokens.get(0).getStatus()).isEqualTo(TokenStatus.REVOKED);
        assertThat(tokens.get(0).getRevokedAt()).isNotNull();
    }

    /**
     * Sécurité bout-en-bout — même garde que {@link #ac0613Sec05_throwsSuperAdminRoleChangeForbidden_whenTargetIsSuperAdminInSameTenant}
     * mais via HTTP complet (token réel, filtre de sécurité réel) : un {@code ROLE_ADMIN} ne peut
     * pas rétrograder un {@code ROLE_SUPER_ADMIN} du même tenant.
     */
    @Test
    void ac0613Http06_returns403_whenTargetIsSuperAdminInSameTenant_platformRoleProtected() throws Exception {
        final User adminA = createUser(tenantAId, "http-admin6@tenant-a.test", "Admin", "Six", "ROLE_ADMIN", true, false);
        final User superAdmin =
                createUser(tenantAId, "http-super6@tenant-a.test", "Super", "Six", "ROLE_SUPER_ADMIN", true, false);
        final String adminToken = issueToken(adminA);

        mockMvc.perform(patch("/api/admin/users/{userId}/role", superAdmin.getId())
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"role\":\"ROLE_USER\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("SUPER_ADMIN_ROLE_PROTECTED"));

        assertThat(userRepository.findById(superAdmin.getId()).orElseThrow().getRole())
                .isEqualTo("ROLE_SUPER_ADMIN");
    }

    // ----------------------------------------------------------------
    // US06.1.4 / US06.1.5 : activation/désactivation de compte — service (BDD réelle)
    // ----------------------------------------------------------------

    @Test
    void ac0614_01_deactivatesUser_persistsInactive_andRevokesAllTokens() {
        final User admin = createUser(tenantAId, "admin-status1@tenant-a.test", "Admin", "One", "ROLE_ADMIN", true, false);
        final User target = createUser(tenantAId, "target-status1@tenant-a.test", "Target", "One", "ROLE_USER", true, false);
        issueToken(target);
        setAuthentication("ROLE_ADMIN");

        final AdminUserDto dto = adminUserService.updateStatus(tenantAId, admin.getId(), target.getId(), AssignableStatus.INACTIVE);

        assertThat(dto.status()).isEqualTo(UserStatus.INACTIVE);
        assertThat(userRepository.findById(target.getId()).orElseThrow().isActive()).isFalse();
        assertThat(accessTokenRepository.findByUserIdOrderByCreatedAtDesc(target.getId()))
                .allSatisfy(t -> assertThat(t.getStatus()).isEqualTo(TokenStatus.REVOKED));
    }

    @Test
    void ac0614Sec01_throwsSelfStatusChangeForbidden_whenAdminTargetsOwnAccount() {
        final User admin = createUser(tenantAId, "admin-status2@tenant-a.test", "Admin", "Two", "ROLE_ADMIN", true, false);
        setAuthentication("ROLE_ADMIN");

        assertThatThrownBy(() ->
                adminUserService.updateStatus(tenantAId, admin.getId(), admin.getId(), AssignableStatus.INACTIVE))
                .isInstanceOf(SelfStatusChangeForbiddenException.class);

        assertThat(userRepository.findById(admin.getId()).orElseThrow().isActive()).isTrue();
    }

    @Test
    void ac0614Sec02_throwsAdminUserNotFound_whenTargetBelongsToAnotherTenant() {
        final User admin = createUser(tenantAId, "admin-status3@tenant-a.test", "Admin", "Three", "ROLE_ADMIN", true, false);
        final User targetInB = createUser(tenantBId, "target-status-b@tenant-b.test", "Target", "B", "ROLE_USER", true, false);
        setAuthentication("ROLE_ADMIN");

        assertThatThrownBy(() ->
                adminUserService.updateStatus(tenantAId, admin.getId(), targetInB.getId(), AssignableStatus.INACTIVE))
                .isInstanceOf(AdminUserNotFoundException.class);

        // Isolation tenant : la ressource d'un autre tenant reste intacte, jamais modifiée.
        assertThat(userRepository.findById(targetInB.getId()).orElseThrow().isActive()).isTrue();
    }

    @Test
    void ac0614Sec03_throwsAccessDenied_whenCallerIsRoleUser() {
        // Symétrique de ac0613Sec04 (US06.1.3) : @PreAuthorize("hasRole('ADMIN')") est porté par
        // AdminUserService#updateStatus lui-même, effectivement évalué par le proxy Spring Method
        // Security — un porteur ROLE_USER est rejeté avant toute lecture en base.
        final User target = createUser(tenantAId, "victim-status@tenant-a.test", "Victim", "One", "ROLE_USER", true, false);
        setAuthentication("ROLE_USER");

        assertThatThrownBy(() ->
                adminUserService.updateStatus(tenantAId, 999L, target.getId(), AssignableStatus.INACTIVE))
                .isInstanceOf(AccessDeniedException.class);

        assertThat(userRepository.findById(target.getId()).orElseThrow().isActive()).isTrue();
    }

    @Test
    void ac0615_01_reactivatesInactiveUser_persistsActive() {
        final User admin = createUser(tenantAId, "admin-status4@tenant-a.test", "Admin", "Four", "ROLE_ADMIN", true, false);
        final User target = createUser(tenantAId, "target-status4@tenant-a.test", "Target", "Four", "ROLE_USER", false, false);
        setAuthentication("ROLE_ADMIN");

        final AdminUserDto dto = adminUserService.updateStatus(tenantAId, admin.getId(), target.getId(), AssignableStatus.ACTIVE);

        assertThat(dto.status()).isEqualTo(UserStatus.ACTIVE);
        assertThat(userRepository.findById(target.getId()).orElseThrow().isActive()).isTrue();
    }

    @Test
    void ac0615_02_reactivatingAlreadyActiveUser_isIdempotent_noError() {
        final User admin = createUser(tenantAId, "admin-status5@tenant-a.test", "Admin", "Five", "ROLE_ADMIN", true, false);
        final User target = createUser(tenantAId, "target-status5@tenant-a.test", "Target", "Five", "ROLE_USER", true, false);
        setAuthentication("ROLE_ADMIN");

        final AdminUserDto dto = adminUserService.updateStatus(tenantAId, admin.getId(), target.getId(), AssignableStatus.ACTIVE);

        assertThat(dto.status()).isEqualTo(UserStatus.ACTIVE);
        assertThat(userRepository.findById(target.getId()).orElseThrow().isActive()).isTrue();
    }

    // ----------------------------------------------------------------
    // US06.1.4 / US06.1.5 : bout-en-bout HTTP (token réel, filtre de sécurité réel)
    // ----------------------------------------------------------------

    @Test
    void ac0614Http01_returns200_andPersistsInactive_whenCallerIsAdminOfSameTenant() throws Exception {
        final User adminA = createUser(tenantAId, "http-status-admin1@tenant-a.test", "Admin", "A", "ROLE_ADMIN", true, false);
        final User target = createUser(tenantAId, "http-status-target1@tenant-a.test", "Target", "A", "ROLE_USER", true, false);
        final String adminToken = issueToken(adminA);

        mockMvc.perform(patch("/api/admin/users/{userId}/status", target.getId())
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"INACTIVE\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(target.getId()))
                .andExpect(jsonPath("$.status").value("INACTIVE"));

        assertThat(userRepository.findById(target.getId()).orElseThrow().isActive()).isFalse();
    }

    @Test
    void ac0614Http02_returns404_whenUserIdBelongsToAnotherTenant_crossTenantIsolation() throws Exception {
        final User adminA = createUser(tenantAId, "http-status-admin2@tenant-a.test", "Admin", "A", "ROLE_ADMIN", true, false);
        final User targetB = createUser(tenantBId, "http-status-target-b@tenant-b.test", "Target", "B", "ROLE_USER", true, false);
        final String adminToken = issueToken(adminA);

        mockMvc.perform(patch("/api/admin/users/{userId}/status", targetB.getId())
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"INACTIVE\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("USER_NOT_FOUND"));

        assertThat(userRepository.findById(targetB.getId()).orElseThrow().isActive()).isTrue();
    }

    @Test
    void ac0614Http03_returns403_whenAdminTargetsOwnAccount_selfDeactivationForbidden() throws Exception {
        final User adminA = createUser(tenantAId, "http-status-self-admin@tenant-a.test", "Self", "Admin", "ROLE_ADMIN", true, false);
        final String adminToken = issueToken(adminA);

        mockMvc.perform(patch("/api/admin/users/{userId}/status", adminA.getId())
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"INACTIVE\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("SELF_STATUS_CHANGE_FORBIDDEN"));

        assertThat(userRepository.findById(adminA.getId()).orElseThrow().isActive()).isTrue();
    }

    @Test
    void ac0614Http04_logsUserDeactivatedAuditEvent_onSuccessfulDeactivation() throws Exception {
        final User adminA = createUser(tenantAId, "http-status-audit-admin@tenant-a.test", "Admin", "Au", "ROLE_ADMIN", true, false);
        final User target = createUser(tenantAId, "http-status-audit-target@tenant-a.test", "Target", "Au", "ROLE_USER", true, false);
        final String adminToken = issueToken(adminA);

        mockMvc.perform(patch("/api/admin/users/{userId}/status", target.getId())
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"INACTIVE\"}"))
                .andExpect(status().isOk());

        final List<AuditEvent> events = auditEventRepository.findByUserIdOrderByCreatedAtDesc(adminA.getId());
        assertThat(events).anySatisfy(e -> assertThat(e.getEventType()).isEqualTo(AuditService.USER_DEACTIVATED));
    }

    /**
     * AC — « Utilisateur désactivé → 401 [403, voir écart documenté sur
     * {@code ac0613Http05_oldTokenRevoked_rejectedImmediatelyAfterRoleChange}] à la prochaine
     * requête (tokens révoqués) » (US06.1.4) — variante bout-en-bout : la désactivation via cet
     * endpoint révoque explicitement les tokens ({@link AdminUserService#updateStatus}), exactement
     * comme le changement de rôle (US06.1.3).
     */
    @Test
    void ac0614Http05_oldTokenRevoked_rejectedImmediatelyAfterDeactivation() throws Exception {
        final User adminA = createUser(tenantAId, "http-status-revoke-admin@tenant-a.test", "Admin", "R", "ROLE_ADMIN", true, false);
        final User targetAdmin =
                createUser(tenantAId, "http-status-revoke-target@tenant-a.test", "Target", "R", "ROLE_ADMIN", true, false);
        final String adminToken = issueToken(adminA);
        final String targetOldToken = issueToken(targetAdmin);

        mockMvc.perform(get("/api/admin/users").header("Authorization", "Bearer " + targetOldToken))
                .andExpect(status().isOk());

        mockMvc.perform(patch("/api/admin/users/{userId}/status", targetAdmin.getId())
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"INACTIVE\"}"))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/admin/users").header("Authorization", "Bearer " + targetOldToken))
                .andExpect(status().isForbidden());

        final List<AccessToken> tokens = accessTokenRepository.findByUserIdOrderByCreatedAtDesc(targetAdmin.getId());
        assertThat(tokens).hasSize(1);
        assertThat(tokens.get(0).getStatus()).isEqualTo(TokenStatus.REVOKED);
    }

    /**
     * AC — « La validation du token dans TokenService vérifie que user.status == ACTIVE (retourne
     * 401 [403, voir écart documenté ci-dessus] sinon, même si le token n'est pas expiré) »
     * (US06.1.4). Contrairement au test précédent, ce test ne passe <strong>jamais</strong> par
     * l'endpoint admin — le compte est désactivé directement en base, sans aucun appel à
     * {@code TokenService#revokeAllForUser}. Si {@link fr.pivot.auth.service.TokenService#validate}
     * ne faisait que s'appuyer sur la révocation explicite (comme pour un changement de rôle avant
     * cette US), ce token — jamais révoqué, {@code status} toujours {@code ACTIVE} en base —
     * resterait valide indéfiniment. Il est ici rejeté malgré tout, ce qui prouve que le statut du
     * compte est bien relu en base à chaque requête, indépendamment de toute révocation — exactement
     * la garantie demandée : une re-désactivation reste fiable même si la révocation explicite
     * était un jour omise ou retardée.
     */
    @Test
    void ac0614Http06_tokenRejected_evenWithoutExplicitRevocation_perRequestStatusCheck() throws Exception {
        final User target = createUser(tenantAId, "http-status-norevoke-target@tenant-a.test", "Target", "N", "ROLE_ADMIN", true, false);
        final String targetToken = issueToken(target);

        mockMvc.perform(get("/api/admin/users").header("Authorization", "Bearer " + targetToken))
                .andExpect(status().isOk());

        // Désactivation directe en base — ne passe pas par AdminUserService.updateStatus, donc
        // aucune révocation de token n'a lieu.
        target.setActive(false);
        userRepository.save(target);
        assertThat(accessTokenRepository.findByUserIdOrderByCreatedAtDesc(target.getId()))
                .allSatisfy(t -> assertThat(t.getStatus()).isEqualTo(TokenStatus.ACTIVE));

        mockMvc.perform(get("/api/admin/users").header("Authorization", "Bearer " + targetToken))
                .andExpect(status().isForbidden());
    }

    @Test
    void ac0615Http01_returns200_andReactivatesUser_fromInactive() throws Exception {
        final User adminA = createUser(tenantAId, "http-reactivate-admin1@tenant-a.test", "Admin", "A", "ROLE_ADMIN", true, false);
        final User target = createUser(tenantAId, "http-reactivate-target1@tenant-a.test", "Target", "A", "ROLE_USER", false, false);
        final String adminToken = issueToken(adminA);

        mockMvc.perform(patch("/api/admin/users/{userId}/status", target.getId())
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"ACTIVE\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"));

        assertThat(userRepository.findById(target.getId()).orElseThrow().isActive()).isTrue();
    }

    @Test
    void ac0615Http02_returns404_whenUserIdBelongsToAnotherTenant_crossTenantIsolation() throws Exception {
        final User adminA = createUser(tenantAId, "http-reactivate-admin2@tenant-a.test", "Admin", "A", "ROLE_ADMIN", true, false);
        final User targetB = createUser(tenantBId, "http-reactivate-target-b@tenant-b.test", "Target", "B", "ROLE_USER", false, false);
        final String adminToken = issueToken(adminA);

        mockMvc.perform(patch("/api/admin/users/{userId}/status", targetB.getId())
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"ACTIVE\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("USER_NOT_FOUND"));
    }

    @Test
    void ac0615Http03_idempotentReactivation_returns200_whenAlreadyActive() throws Exception {
        final User adminA = createUser(tenantAId, "http-reactivate-admin3@tenant-a.test", "Admin", "A", "ROLE_ADMIN", true, false);
        final User target = createUser(tenantAId, "http-reactivate-target3@tenant-a.test", "Target", "A", "ROLE_USER", true, false);
        final String adminToken = issueToken(adminA);

        mockMvc.perform(patch("/api/admin/users/{userId}/status", target.getId())
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"ACTIVE\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"));

        assertThat(userRepository.findById(target.getId()).orElseThrow().isActive()).isTrue();
    }

    @Test
    void ac0615Http04_logsUserReactivatedAuditEvent_withActorIdAndTargetUserId() throws Exception {
        final User adminA = createUser(tenantAId, "http-reactivate-audit-admin@tenant-a.test", "Admin", "Au", "ROLE_ADMIN", true, false);
        final User target = createUser(tenantAId, "http-reactivate-audit-target@tenant-a.test", "Target", "Au", "ROLE_USER", false, false);
        final String adminToken = issueToken(adminA);

        mockMvc.perform(patch("/api/admin/users/{userId}/status", target.getId())
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"ACTIVE\"}"))
                .andExpect(status().isOk());

        final List<AuditEvent> events = auditEventRepository.findByUserIdOrderByCreatedAtDesc(adminA.getId());
        assertThat(events).anySatisfy(e -> {
            assertThat(e.getEventType()).isEqualTo(AuditService.USER_REACTIVATED);
            // Round-trip through the JSONB column reformats the text (Postgres jsonb does not
            // preserve original whitespace/key order) — assert on the values without depending
            // on exact formatting rather than a literal substring match.
            final String meta = e.getMeta().replace(" ", "");
            assertThat(meta).contains("\"targetUserId\":" + target.getId())
                    .contains("\"actorId\":" + adminA.getId());
        });
    }

    /**
     * Preuve bout-en-bout du cycle complet désactivation → réactivation (US06.1.4 + US06.1.5) :
     * un token émis pendant que le compte est {@code INACTIVE} est rejeté ({@code 403}) — la
     * vérification {@code user.isActive()} de {@link fr.pivot.auth.service.TokenService#validate}
     * s'applique même à un token jamais concerné par une révocation puisqu'émis après coup — puis
     * ce même token redevient utilisable dès que l'admin réactive le compte via cet endpoint,
     * sans qu'il soit nécessaire de ré-émettre un nouveau token.
     */
    @Test
    void ac0615Http05_tokenIssuedWhileInactive_rejectedThenAcceptedAfterReactivation() throws Exception {
        final User adminA = createUser(tenantAId, "http-reactivate-cycle-admin@tenant-a.test", "Admin", "C", "ROLE_ADMIN", true, false);
        final User target = createUser(tenantAId, "http-reactivate-cycle-target@tenant-a.test", "Target", "C", "ROLE_ADMIN", false, false);
        final String adminToken = issueToken(adminA);
        final String targetToken = issueToken(target);

        mockMvc.perform(get("/api/admin/users").header("Authorization", "Bearer " + targetToken))
                .andExpect(status().isForbidden());

        mockMvc.perform(patch("/api/admin/users/{userId}/status", target.getId())
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"ACTIVE\"}"))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/admin/users").header("Authorization", "Bearer " + targetToken))
                .andExpect(status().isOk());
    }

    // ----------------------------------------------------------------
    // Helpers
    // ----------------------------------------------------------------

    private String issueToken(final User user) {
        return tokenService.issue(user, "fp-" + user.getId() + "-" + System.nanoTime(), "Test device",
                "Mozilla/5.0 (test)", "203.0.113.1", AuthMethod.PASSWORD, false).rawToken();
    }

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

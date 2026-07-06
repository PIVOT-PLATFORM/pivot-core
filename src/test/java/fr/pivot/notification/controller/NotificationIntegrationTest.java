package fr.pivot.notification.controller;

import fr.pivot.AbstractIntegrationTest;
import fr.pivot.auth.dto.AssignableRole;
import fr.pivot.auth.dto.AssignableStatus;
import fr.pivot.auth.entity.AuthMethod;
import fr.pivot.auth.entity.User;
import fr.pivot.auth.repository.UserRepository;
import fr.pivot.auth.service.AdminUserService;
import fr.pivot.auth.service.TokenService;
import fr.pivot.notification.dto.NotificationDto;
import fr.pivot.notification.entity.Notification;
import fr.pivot.notification.service.NotificationPayload;
import fr.pivot.notification.service.NotificationService;
import fr.pivot.notification.service.NotificationType;
import fr.pivot.tenant.entity.Tenant;
import fr.pivot.tenant.repository.TenantRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Tests d'intégration (PostgreSQL via Testcontainers, contexte Spring réel) pour l'infrastructure
 * notifications in-app (EN-NOTIF).
 *
 * <p>Traçabilité AC :
 * <ul>
 *   <li>Isolation tenant — {@link #sec_neverReturnsAnotherUsersNotifications_crossTenantIsolation()},
 *       {@link #sec_listReturnsEmpty_whenTenantIdDoesNotMatchOwner_defenseInDepth()}</li>
 *   <li>Producteur → consommateur (US06.1.3, US06.1.4, réellement câblés dans
 *       {@link AdminUserService}) —
 *       {@link #producerConsumer_roleChange_createsVisibleNotification()},
 *       {@link #producerConsumer_accountDeactivation_createsVisibleNotification()},
 *       {@link #producerConsumer_accountReactivation_neverCreatesNotification()}</li>
 *   <li>unread-count — {@link #unreadCount_reflectsCreationAndReading_endToEnd()}</li>
 *   <li>Contrat HTTP complet (pagination, tri, 404 cross-user) —
 *       {@link #http_list_returnsPagedNotifications_sortedCreatedAtDesc()},
 *       {@link #http_markAsRead_returns404_whenNotificationBelongsToAnotherUser()}</li>
 * </ul>
 */
class NotificationIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private NotificationService notificationService;

    @Autowired
    private AdminUserService adminUserService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private TenantRepository tenantRepository;

    @Autowired
    private TokenService tokenService;

    @Autowired
    private WebApplicationContext webApplicationContext;

    private Long tenantAId;
    private Long tenantBId;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        tenantAId = createTenant("notif-it-tenant-a");
        tenantBId = createTenant("notif-it-tenant-b");
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext)
                .apply(springSecurity())
                .build();
    }

    @AfterEach
    void tearDown() {
        // Filtre au niveau SQL (Specification), jamais via u.getTenant() côté Java — tenant est
        // @ManyToOne(LAZY), l'entité étant déjà détachée à ce stade (même motif que
        // AdminUserIntegrationTest#tearDown). Suppression des utilisateurs de test : les
        // notifications rattachées (FK user_id/tenant_id ON DELETE CASCADE — V1__schema_init.sql)
        // sont supprimées automatiquement, pas de nettoyage explicite de NotificationRepository
        // nécessaire.
        final Specification<User> ofTestTenants =
                (root, query, cb) -> root.get("tenant").get("id").in(tenantAId, tenantBId);
        final List<User> testUsers = userRepository.findAll(ofTestTenants);
        testUsers.forEach(u -> userRepository.deleteById(u.getId()));
        tenantRepository.deleteById(tenantAId);
        tenantRepository.deleteById(tenantBId);
        SecurityContextHolder.clearContext();
    }

    // ----------------------------------------------------------------
    // AC : isolation tenant
    // ----------------------------------------------------------------

    @Test
    void sec_neverReturnsAnotherUsersNotifications_crossTenantIsolation() {
        final User userA = createUser(tenantAId, "alice@notif-a.test");
        final User userB = createUser(tenantBId, "bob@notif-b.test");
        notificationService.create(userA.getId(), NotificationType.ROLE_CHANGED, NotificationPayload.of("ROLE_ADMIN"));
        notificationService.create(userB.getId(), NotificationType.ACCOUNT_DEACTIVATED, NotificationPayload.of());

        final Page<?> pageA = notificationService.list(userA.getId(), tenantAId, PageRequest.of(0, 20));
        final Page<?> pageB = notificationService.list(userB.getId(), tenantBId, PageRequest.of(0, 20));

        assertThat(pageA.getTotalElements()).isEqualTo(1);
        assertThat(pageB.getTotalElements()).isEqualTo(1);
    }

    @Test
    void sec_listReturnsEmpty_whenTenantIdDoesNotMatchOwner_defenseInDepth() {
        // Défense en profondeur : même avec le bon userId, un tenantId erroné (ex. falsifié)
        // ne doit jamais retourner de résultat — voir NotificationRepository#findByUserIdAndTenantId.
        final User userA = createUser(tenantAId, "carol@notif-a.test");
        notificationService.create(userA.getId(), NotificationType.ROLE_CHANGED, NotificationPayload.of("ROLE_USER"));

        final Page<?> wrongTenant = notificationService.list(userA.getId(), tenantBId, PageRequest.of(0, 20));

        assertThat(wrongTenant.getTotalElements()).isZero();
    }

    // ----------------------------------------------------------------
    // AC : producteur → consommateur (US06.1.3, US06.1.4 — câblage réel AdminUserService)
    // ----------------------------------------------------------------

    @Test
    void producerConsumer_roleChange_createsVisibleNotification() {
        final User admin = createUser(tenantAId, "admin@notif-a.test");
        final User target = createUser(tenantAId, "target@notif-a.test");
        setAuthentication("ROLE_ADMIN");

        adminUserService.updateRole(tenantAId, admin.getId(), target.getId(), AssignableRole.ROLE_ADMIN);

        final Page<NotificationDto> page =
                notificationService.list(target.getId(), tenantAId, PageRequest.of(0, 20));
        assertThat(page.getTotalElements()).isEqualTo(1);
        assertThat(page.getContent().get(0).type()).isEqualTo(NotificationType.ROLE_CHANGED);
        assertThat(page.getContent().get(0).body()).contains("ROLE_ADMIN");
    }

    @Test
    void producerConsumer_accountDeactivation_createsVisibleNotification() {
        final User admin = createUser(tenantAId, "admin2@notif-a.test");
        final User target = createUser(tenantAId, "target2@notif-a.test");
        setAuthentication("ROLE_ADMIN");

        adminUserService.updateStatus(tenantAId, admin.getId(), target.getId(), AssignableStatus.INACTIVE);

        final Page<NotificationDto> page =
                notificationService.list(target.getId(), tenantAId, PageRequest.of(0, 20));
        assertThat(page.getTotalElements()).isEqualTo(1);
        assertThat(page.getContent().get(0).type()).isEqualTo(NotificationType.ACCOUNT_DEACTIVATED);
    }

    @Test
    void producerConsumer_accountReactivation_neverCreatesNotification() {
        // US06.1.5 (réactivation) n'est volontairement pas un producteur EN-NOTIF.
        final User admin = createUser(tenantAId, "admin3@notif-a.test");
        final User target = createUser(tenantAId, "target3@notif-a.test");
        setAuthentication("ROLE_ADMIN");
        adminUserService.updateStatus(tenantAId, admin.getId(), target.getId(), AssignableStatus.INACTIVE);

        adminUserService.updateStatus(tenantAId, admin.getId(), target.getId(), AssignableStatus.ACTIVE);

        final Page<NotificationDto> page =
                notificationService.list(target.getId(), tenantAId, PageRequest.of(0, 20));
        // Une seule notification (désactivation) — la réactivation n'en ajoute pas de seconde.
        assertThat(page.getTotalElements()).isEqualTo(1);
        assertThat(page.getContent().get(0).type()).isEqualTo(NotificationType.ACCOUNT_DEACTIVATED);
    }

    // ----------------------------------------------------------------
    // AC : unread-count
    // ----------------------------------------------------------------

    @Test
    void unreadCount_reflectsCreationAndReading_endToEnd() {
        final User user = createUser(tenantAId, "dana@notif-a.test");
        final Notification n1 = notificationService.create(
                user.getId(), NotificationType.ROLE_CHANGED, NotificationPayload.of("ROLE_ADMIN"));
        notificationService.create(user.getId(), NotificationType.ACCOUNT_DEACTIVATED, NotificationPayload.of());
        notificationService.create(user.getId(), NotificationType.ACCOUNT_DEACTIVATED, NotificationPayload.of());

        assertThat(notificationService.unreadCount(user.getId(), tenantAId)).isEqualTo(3L);

        notificationService.markAsRead(n1.getId(), user.getId());
        assertThat(notificationService.unreadCount(user.getId(), tenantAId)).isEqualTo(2L);

        final int updated = notificationService.markAllAsRead(user.getId(), tenantAId);
        assertThat(updated).isEqualTo(2);
        assertThat(notificationService.unreadCount(user.getId(), tenantAId)).isZero();
    }

    // ----------------------------------------------------------------
    // AC : contrat HTTP — pagination/tri, unread-count, read, read-all
    // ----------------------------------------------------------------

    @Test
    void http_list_returnsPagedNotifications_sortedCreatedAtDesc() throws Exception {
        final User user = createUser(tenantAId, "erin@notif-a.test");
        final String token = issueToken(user);
        // createdAt has no public setter (immutable audit column) — no Thread.sleep (Sonar
        // java:S2925) needed to guarantee a distinct, strictly later timestamp for the second
        // notification: each create() below is a full JPA persist + flush + commit against the
        // real Testcontainers Postgres instance, which alone takes well over 1ms, far above
        // Instant.now() resolution.
        notificationService.create(user.getId(), NotificationType.ROLE_CHANGED, NotificationPayload.of("ROLE_ADMIN"));
        notificationService.create(user.getId(), NotificationType.ACCOUNT_DEACTIVATED, NotificationPayload.of());

        mockMvc.perform(get("/notifications").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(2))
                .andExpect(jsonPath("$.content[0].type").value("ACCOUNT_DEACTIVATED"))
                .andExpect(jsonPath("$.content[1].type").value("ROLE_CHANGED"))
                .andExpect(jsonPath("$.size").value(20));
    }

    @Test
    void http_unreadCount_andMarkAsRead_andMarkAllAsRead() throws Exception {
        final User user = createUser(tenantAId, "frank@notif-a.test");
        final String token = issueToken(user);
        final Notification n1 = notificationService.create(
                user.getId(), NotificationType.ROLE_CHANGED, NotificationPayload.of("ROLE_ADMIN"));
        notificationService.create(user.getId(), NotificationType.ACCOUNT_DEACTIVATED, NotificationPayload.of());

        mockMvc.perform(get("/notifications/unread-count").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.count").value(2));

        mockMvc.perform(patch("/notifications/{id}/read", n1.getId())
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.readAt").isNotEmpty());

        mockMvc.perform(get("/notifications/unread-count").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.count").value(1));

        mockMvc.perform(patch("/notifications/read-all").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.updatedCount").value(1));

        mockMvc.perform(get("/notifications/unread-count").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.count").value(0));
    }

    @Test
    void http_markAsRead_returns404_whenNotificationBelongsToAnotherUser() throws Exception {
        final User owner = createUser(tenantAId, "gina@notif-a.test");
        final User intruder = createUser(tenantAId, "harry@notif-a.test");
        final String intruderToken = issueToken(intruder);
        final Notification ownerNotification = notificationService.create(
                owner.getId(), NotificationType.ROLE_CHANGED, NotificationPayload.of("ROLE_ADMIN"));

        mockMvc.perform(patch("/notifications/{id}/read", ownerNotification.getId())
                        .header("Authorization", "Bearer " + intruderToken))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("NOTIFICATION_NOT_FOUND"));
    }

    @Test
    void http_list_returns403_whenNoToken() throws Exception {
        // No AuthenticationEntryPoint is registered in SecurityConfig (httpBasic/formLogin both
        // disabled — stateless opaque-token auth only), so Spring Security's
        // ExceptionTranslationFilter falls back to Http403ForbiddenEntryPoint for an
        // unauthenticated request: 403, not 401 — existing, application-wide SecurityConfig
        // behaviour (see SuperAdminTenantIntegrationTest#ac_security_http_deniesWith403_whenCallerUnauthenticated),
        // not specific to this endpoint. NotificationController#resolveActor's own 401 branch is
        // for a different case: an Authentication present but without a real User principal.
        mockMvc.perform(get("/notifications"))
                .andExpect(status().isForbidden());
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

    private User createUser(final Long tenantId, final String email) {
        final Tenant tenant = tenantRepository.findById(tenantId).orElseThrow();
        final User user = new User();
        user.setTenant(tenant);
        user.setEmail(email);
        user.setFirstName("Test");
        user.setLastName("User");
        user.setRole("ROLE_USER");
        user.setActive(true);
        user.setBlocked(false);
        return userRepository.save(user);
    }

    private static void setAuthentication(final String role) {
        final UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken(
                "test-principal", null, List.of(new SimpleGrantedAuthority(role)));
        SecurityContextHolder.getContext().setAuthentication(auth);
    }
}

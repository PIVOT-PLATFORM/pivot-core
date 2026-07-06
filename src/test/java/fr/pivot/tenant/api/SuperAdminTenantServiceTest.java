package fr.pivot.tenant.api;

import fr.pivot.auth.entity.User;
import fr.pivot.auth.exception.RateLimitException;
import fr.pivot.auth.repository.TenantUserCountProjection;
import fr.pivot.auth.repository.UserRepository;
import fr.pivot.auth.service.AuditService;
import fr.pivot.auth.service.RateLimiterService;
import fr.pivot.tenant.entity.Tenant;
import fr.pivot.tenant.repository.TenantRepository;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests unitaires pour {@link SuperAdminTenantService} — US06.2.3 « Super admin liste tous les
 * tenants », US06.2.1 « Super admin crée un tenant » et US06.2.2 « Super admin désactive un
 * tenant ».
 *
 * <p>Le RBAC ({@code @PreAuthorize}) n'est pas exercé ici (service instancié directement, hors
 * proxy Spring) — couvert par {@link SuperAdminTenantIntegrationTest}. Rate limiting exercé avec
 * un {@link RateLimiterService} mocké (pas de Redis), même convention que
 * {@code RegistrationServiceTest} — le câblage Redis réel de bout en bout est couvert séparément
 * par {@link SuperAdminTenantIntegrationTest}.
 *
 * <table>
 *   <caption>Traçabilité AC → test</caption>
 *   <tr><th>AC</th><th>Test</th></tr>
 *   <tr><td>Liste paginée avec userCount par lot</td>
 *       <td>{@link #ac3_1_listTenants_mapsUserCountFromBatchProjection_whenTenantHasUsers()}</td></tr>
 *   <tr><td>Crée un tenant avec nom, slug, plan, auth_mode</td>
 *       <td>{@link #createTenant_persistsTenant_withRequestedFields()}</td></tr>
 *   <tr><td>Tenant créé avec is_active: true</td>
 *       <td>{@link #createTenant_persistsTenant_withRequestedFields()}</td></tr>
 *   <tr><td>Audit event TenantCreated</td>
 *       <td>{@link #createTenant_logsAuditEvent_onSuccess()}</td></tr>
 *   <tr><td>Slug unique (409 si doublon)</td>
 *       <td>{@link #createTenant_throwsConflict_whenSlugAlreadyExists()}</td></tr>
 *   <tr><td>Slug réservé → 422</td>
 *       <td>{@link #createTenant_throwsReserved_whenSlugIsReservedWord()}</td></tr>
 *   <tr><td>Rate limit 10/heure → 429 + audit TenantCreationRateLimitExceeded</td>
 *       <td>{@link #createTenant_throwsRateLimit_andLogsAudit_whenLimitExceeded()}</td></tr>
 *   <tr><td>Réponse retourne l'ID et une URL d'invitation</td>
 *       <td>{@link #createTenant_returnsIdAndInvitationUrl()}</td></tr>
 *   <tr><td>check-slug — disponible</td>
 *       <td>{@link #checkSlugAvailability_returnsAvailable_whenFreeAndValid()}</td></tr>
 *   <tr><td>check-slug — réservé</td>
 *       <td>{@link #checkSlugAvailability_returnsReserved_whenSlugIsReservedWord()}</td></tr>
 *   <tr><td>check-slug — pris</td>
 *       <td>{@link #checkSlugAvailability_returnsTaken_whenSlugAlreadyExists()}</td></tr>
 *   <tr><td>check-slug — format invalide</td>
 *       <td>{@link #checkSlugAvailability_returnsInvalidFormat_whenSlugFailsRegex()}</td></tr>
 *   <tr><td>Désactivation — révocation bulk + timestamp posé</td>
 *       <td>{@link #updateStatus_shouldDeactivateTenant_andStampInvalidationTimestamp()}</td></tr>
 *   <tr><td>Désactivation — tenant introuvable → 404</td>
 *       <td>{@link #updateStatus_shouldThrowTenantNotFound_whenTenantMissing()}</td></tr>
 *   <tr><td>Désactivation — protection du tenant système → 403</td>
 *       <td>{@link #updateStatus_shouldThrowSystemTenantProtected_whenTargetIsSystemTenant()}</td></tr>
 * </table>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SuperAdminTenantServiceTest {

    private static final String APP_URL = "https://app.pivot.test";
    private static final String SLUG = "acme-corp";
    private static final String AUTH_MODE_LOCAL = "LOCAL";
    private static final String IP = "127.0.0.1";
    private static final String USER_AGENT = "junit";
    private static final Long TENANT_ID = 42L;
    private static final String SYSTEM_SLUG = "pivot-saas";

    @Mock private TenantRepository tenantRepository;
    @Mock private UserRepository userRepository;
    @Mock private RateLimiterService rateLimiter;
    @Mock private AuditService auditService;

    private SuperAdminTenantService service;
    private User superAdmin;

    @BeforeEach
    void setUp() {
        service = new SuperAdminTenantService(
                tenantRepository, userRepository, rateLimiter, auditService, APP_URL, SYSTEM_SLUG);
        superAdmin = new User();
        when(rateLimiter.tenantCreationBucket(any())).thenCallRealMethod();
        when(rateLimiter.checkAndRecord(any(), anyInt(), any())).thenReturn(true);
        when(tenantRepository.findBySlug(any())).thenReturn(Optional.empty());
        when(tenantRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
    }

    private CreateTenantRequest validRequest() {
        return new CreateTenantRequest("Acme Corp", SLUG, "SAAS", AUTH_MODE_LOCAL);
    }

    // ----------------------------------------------------------------
    // listTenants — US06.2.3
    // ----------------------------------------------------------------

    @Test
    void ac3_1_listTenants_mapsUserCountFromBatchProjection_whenTenantHasUsers() {
        final Tenant tenant = buildTenant(1L, "acme", "Acme Corp");
        final Pageable pageable = PageRequest.of(0, 20);
        final Page<Tenant> tenantPage = new PageImpl<>(List.of(tenant), pageable, 1);
        when(tenantRepository.findAll(any(Specification.class), any(Pageable.class))).thenReturn(tenantPage);

        final TenantUserCountProjection projection = projection(1L, 7L);
        when(userRepository.countActiveUsersByTenantIds(anyList())).thenReturn(List.of(projection));

        final Page<TenantSummaryDto> result = service.listTenants(null, null, null, null, pageable);

        assertThat(result.getContent()).hasSize(1);
        final TenantSummaryDto dto = result.getContent().get(0);
        assertThat(dto.id()).isEqualTo(1L);
        assertThat(dto.slug()).isEqualTo("acme");
        assertThat(dto.userCount()).isEqualTo(7L);
    }

    @Test
    void ac3_2_listTenants_defaultsUserCountToZero_whenTenantHasNoUsers() {
        final Tenant tenant = buildTenant(2L, "empty-tenant", "Empty Tenant");
        final Pageable pageable = PageRequest.of(0, 20);
        final Page<Tenant> tenantPage = new PageImpl<>(List.of(tenant), pageable, 1);
        when(tenantRepository.findAll(any(Specification.class), any(Pageable.class))).thenReturn(tenantPage);
        // No projection row for tenant 2 — simulates GROUP BY producing no row for a tenant with 0 users.
        when(userRepository.countActiveUsersByTenantIds(anyList())).thenReturn(List.of());

        final Page<TenantSummaryDto> result = service.listTenants(null, null, null, null, pageable);

        assertThat(result.getContent().get(0).userCount()).isZero();
    }

    @Test
    void ac3_3_listTenants_skipsUserCountQuery_whenNoTenantsInPage() {
        final Pageable pageable = PageRequest.of(5, 20);
        final Page<Tenant> emptyPage = new PageImpl<>(List.of(), pageable, 0);
        when(tenantRepository.findAll(any(Specification.class), any(Pageable.class))).thenReturn(emptyPage);

        final Page<TenantSummaryDto> result = service.listTenants(null, null, null, null, pageable);

        assertThat(result.getContent()).isEmpty();
        verify(userRepository, never()).countActiveUsersByTenantIds(anyList());
    }

    // ----------------------------------------------------------------
    // createTenant — happy path
    // ----------------------------------------------------------------

    @Test
    void createTenant_persistsTenant_withRequestedFields() {
        service.createTenant(validRequest(), superAdmin, IP, USER_AGENT);

        final ArgumentCaptor<Tenant> captor = ArgumentCaptor.forClass(Tenant.class);
        verify(tenantRepository).save(captor.capture());
        final Tenant saved = captor.getValue();
        assertThat(saved.getName()).isEqualTo("Acme Corp");
        assertThat(saved.getSlug()).isEqualTo(SLUG);
        assertThat(saved.getPlan()).isEqualTo("SAAS");
        assertThat(saved.getAuthMode()).isEqualTo(AUTH_MODE_LOCAL);
        assertThat(saved.isActive()).isTrue();
    }

    @Test
    void createTenant_returnsIdAndInvitationUrl() {
        when(tenantRepository.save(any())).thenAnswer(invocation -> {
            final Tenant tenant = invocation.getArgument(0);
            ReflectionTestUtils.setField(tenant, "id", 42L);
            return tenant;
        });

        final CreateTenantResponse response = service.createTenant(validRequest(), superAdmin, IP, USER_AGENT);

        assertThat(response.id()).isEqualTo(42L);
        assertThat(response.slug()).isEqualTo(SLUG);
        assertThat(response.invitationUrl()).isEqualTo(APP_URL + "/auth/register?tenant=" + SLUG);
    }

    @Test
    void createTenant_logsAuditEvent_onSuccess() {
        service.createTenant(validRequest(), superAdmin, IP, USER_AGENT);

        verify(auditService).log(eq(superAdmin), any(Tenant.class), eq(AuditService.TENANT_CREATED),
                eq(IP), eq(USER_AGENT));
    }

    // ----------------------------------------------------------------
    // Slug — réservé (422) et doublon (409)
    // ----------------------------------------------------------------

    @Test
    void createTenant_throwsReserved_whenSlugIsReservedWord() {
        final CreateTenantRequest request = new CreateTenantRequest("Admin Co", "admin", "SAAS", AUTH_MODE_LOCAL);

        assertThatThrownBy(() -> service.createTenant(request, superAdmin, IP, USER_AGENT))
                .isInstanceOf(ReservedTenantSlugException.class);
        verify(tenantRepository, never()).save(any());
    }

    @Test
    void createTenant_throwsConflict_whenSlugAlreadyExists() {
        when(tenantRepository.findBySlug(SLUG)).thenReturn(Optional.of(new Tenant()));
        final CreateTenantRequest request = validRequest();

        assertThatThrownBy(() -> service.createTenant(request, superAdmin, IP, USER_AGENT))
                .isInstanceOf(TenantSlugAlreadyExistsException.class);
        verify(tenantRepository, never()).save(any());
    }

    // ----------------------------------------------------------------
    // Rate limit — 10/heure, 429 + audit
    // ----------------------------------------------------------------

    @Test
    void createTenant_throwsRateLimit_andLogsAudit_whenLimitExceeded() {
        when(rateLimiter.checkAndRecord(any(), anyInt(), any())).thenReturn(false);
        when(rateLimiter.getRemainingSeconds(any())).thenReturn(1800L);
        final CreateTenantRequest request = validRequest();

        assertThatThrownBy(() -> service.createTenant(request, superAdmin, IP, USER_AGENT))
                .isInstanceOf(RateLimitException.class)
                .satisfies(ex -> assertThat(((RateLimitException) ex).getRetryAfterSeconds()).isEqualTo(1800L));

        verify(auditService).log(superAdmin, null, AuditService.TENANT_CREATION_RATE_LIMIT_EXCEEDED,
                IP, USER_AGENT, null);
        verify(tenantRepository, never()).findBySlug(any());
        verify(tenantRepository, never()).save(any());
    }

    @Test
    void createTenant_usesTenLimitPerHour_asConfiguredRate() {
        service.createTenant(validRequest(), superAdmin, IP, USER_AGENT);

        verify(rateLimiter, times(1)).checkAndRecord(any(), eq(10), eq(Duration.ofHours(1)));
    }

    // ----------------------------------------------------------------
    // check-slug
    // ----------------------------------------------------------------

    @ParameterizedTest
    @MethodSource("slugAvailabilityCases")
    void checkSlugAvailability_returnsExpectedAvailabilityAndReason(
            final String slug, final boolean expectedAvailable, final String expectedReason) {
        final SlugAvailabilityResponse response = service.checkSlugAvailability(slug);

        assertThat(response.available()).isEqualTo(expectedAvailable);
        assertThat(response.reason()).isEqualTo(expectedReason);
    }

    private static Stream<Arguments> slugAvailabilityCases() {
        return Stream.of(
                Arguments.of("brand-new-tenant", true, null),
                Arguments.of("api", false, "RESERVED"),
                Arguments.of("AB", false, "INVALID_FORMAT"),
                Arguments.of((String) null, false, "INVALID_FORMAT"));
    }

    @Test
    void checkSlugAvailability_returnsTaken_whenSlugAlreadyExists() {
        when(tenantRepository.findBySlug("taken-slug")).thenReturn(Optional.of(new Tenant()));

        final SlugAvailabilityResponse response = service.checkSlugAvailability("taken-slug");

        assertThat(response.available()).isFalse();
        assertThat(response.reason()).isEqualTo("TAKEN");
    }

    // ----------------------------------------------------------------
    // updateStatus — US06.2.2
    // ----------------------------------------------------------------

    @Test
    void updateStatus_shouldDeactivateTenant_andStampInvalidationTimestamp() {
        final Tenant tenant = new Tenant();
        tenant.setSlug("acme");
        tenant.setActive(true);
        when(tenantRepository.findById(TENANT_ID)).thenReturn(Optional.of(tenant));
        when(tenantRepository.saveAndFlush(tenant)).thenReturn(tenant);

        final Tenant result = service.updateStatus(TENANT_ID, "INACTIVE");

        assertThat(result.isActive()).isFalse();
        assertThat(result.getTenantInvalidationTimestamp()).isNotNull();
        verify(tenantRepository).saveAndFlush(tenant);
    }

    @Test
    void updateStatus_shouldThrowTenantNotFound_whenTenantMissing() {
        when(tenantRepository.findById(TENANT_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.updateStatus(TENANT_ID, "INACTIVE"))
                .isInstanceOf(TenantNotFoundException.class);

        verify(tenantRepository, never()).saveAndFlush(any());
    }

    @Test
    void updateStatus_shouldThrowSystemTenantProtected_whenTargetIsSystemTenant() {
        final Tenant systemTenant = new Tenant();
        systemTenant.setSlug(SYSTEM_SLUG);
        systemTenant.setActive(true);
        when(tenantRepository.findById(TENANT_ID)).thenReturn(Optional.of(systemTenant));

        assertThatThrownBy(() -> service.updateStatus(TENANT_ID, "INACTIVE"))
                .isInstanceOf(SystemTenantProtectedException.class);

        verify(tenantRepository, never()).saveAndFlush(any());
        // Untouched by the rejected attempt.
        assertThat(systemTenant.isActive()).isTrue();
        assertThat(systemTenant.getTenantInvalidationTimestamp()).isNull();
    }

    @Test
    void updateStatus_shouldMatchSystemTenantSlug_caseInsensitively() {
        final Tenant systemTenant = new Tenant();
        systemTenant.setSlug(SYSTEM_SLUG.toUpperCase());
        when(tenantRepository.findById(TENANT_ID)).thenReturn(Optional.of(systemTenant));

        assertThatThrownBy(() -> service.updateStatus(TENANT_ID, "INACTIVE"))
                .isInstanceOf(SystemTenantProtectedException.class);
    }

    @Test
    void updateStatus_shouldThrowUnsupportedStatus_whenStatusIsNotInactive() {
        assertThatThrownBy(() -> service.updateStatus(TENANT_ID, "ACTIVE"))
                .isInstanceOf(UnsupportedTenantStatusException.class);

        // Rejected before even looking up the tenant.
        verify(tenantRepository, never()).findById(any());
    }

    @Test
    void updateStatus_shouldThrowUnsupportedStatus_whenStatusIsBlankOrUnknown() {
        assertThatThrownBy(() -> service.updateStatus(TENANT_ID, "deleted"))
                .isInstanceOf(UnsupportedTenantStatusException.class);
    }

    // ----------------------------------------------------------------
    // Helpers
    // ----------------------------------------------------------------

    private static Tenant buildTenant(final Long id, final String slug, final String name) {
        final Tenant tenant = new Tenant();
        setId(tenant, id);
        tenant.setSlug(slug);
        tenant.setName(name);
        tenant.setPlan("SAAS");
        tenant.setAuthMode("SAAS");
        tenant.setActive(true);
        return tenant;
    }

    private static void setId(final Tenant tenant, final Long id) {
        try {
            final var field = Tenant.class.getDeclaredField("id");
            field.setAccessible(true);
            field.set(tenant, id);
        } catch (final ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    private static TenantUserCountProjection projection(final Long tenantId, final long userCount) {
        return new TenantUserCountProjection() {
            @Override
            public Long getTenantId() {
                return tenantId;
            }

            @Override
            public long getUserCount() {
                return userCount;
            }
        };
    }
}

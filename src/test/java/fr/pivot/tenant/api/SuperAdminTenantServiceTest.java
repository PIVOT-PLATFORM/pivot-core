package fr.pivot.tenant.api;

import fr.pivot.auth.repository.TenantUserCountProjection;
import fr.pivot.auth.repository.UserRepository;
import fr.pivot.tenant.entity.Tenant;
import fr.pivot.tenant.repository.TenantRepository;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests unitaires pour {@link SuperAdminTenantService} — US06.2.3.
 *
 * <p>Le RBAC ({@code @PreAuthorize}) n'est pas exercé ici (service instancié directement, hors
 * proxy Spring) — couvert par {@code SuperAdminTenantIntegrationTest}. Ce test se concentre sur
 * le mapping DTO et le calcul de {@code userCount} par lot (sans N+1).
 */
@ExtendWith(MockitoExtension.class)
class SuperAdminTenantServiceTest {

    @Mock
    private TenantRepository tenantRepository;

    @Mock
    private UserRepository userRepository;

    private SuperAdminTenantService service;

    @org.junit.jupiter.api.BeforeEach
    void setUp() {
        service = new SuperAdminTenantService(tenantRepository, userRepository);
    }

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
        verify(userRepository, org.mockito.Mockito.never()).countActiveUsersByTenantIds(anyList());
    }

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

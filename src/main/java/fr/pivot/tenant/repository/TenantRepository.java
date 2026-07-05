package fr.pivot.tenant.repository;

import fr.pivot.tenant.entity.Tenant;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import java.util.Optional;

/**
 * Accès BDD aux tenants ({@code public.tenants}).
 *
 * <p>Étend {@link JpaSpecificationExecutor} pour supporter les filtres dynamiques et optionnels
 * du listing super-admin ({@code GET /api/superadmin/tenants} — US06.2.3), combinés via
 * {@link fr.pivot.tenant.api.TenantSpecifications}.
 */
public interface TenantRepository extends JpaRepository<Tenant, Long>, JpaSpecificationExecutor<Tenant> {
    Optional<Tenant> findBySlug(String slug);
    Optional<Tenant> findByIdAndActiveTrue(Long id);
}

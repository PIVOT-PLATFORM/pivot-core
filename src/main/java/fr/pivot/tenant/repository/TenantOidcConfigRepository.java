package fr.pivot.tenant.repository;

import fr.pivot.tenant.entity.TenantOidcConfig;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;

public interface TenantOidcConfigRepository extends JpaRepository<TenantOidcConfig, Long> {
    Optional<TenantOidcConfig> findByTenantId(Long tenantId);
}

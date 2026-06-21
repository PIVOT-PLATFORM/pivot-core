package fr.pivot.tenant.repository;

import fr.pivot.tenant.entity.Tenant;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;

public interface TenantRepository extends JpaRepository<Tenant, Long> {
    Optional<Tenant> findBySlug(String slug);
    Optional<Tenant> findByIdAndActiveTrue(Long id);
}

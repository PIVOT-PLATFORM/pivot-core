package fr.pivot.collaboratif.session.wordcloud;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

/**
 * Repository for {@link TenantWordBlocklist}.
 */
public interface TenantWordBlocklistRepository extends JpaRepository<TenantWordBlocklist, UUID> {

    /**
     * Checks whether a word is blocklisted for a tenant.
     *
     * @param tenantId the tenant
     * @param word     the normalized word
     * @return {@code true} if blocked
     */
    boolean existsByTenantIdAndWord(Long tenantId, String word);
}

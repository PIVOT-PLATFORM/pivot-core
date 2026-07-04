package fr.pivot.auth.repository;

import fr.pivot.auth.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.List;
import java.util.Optional;

public interface UserRepository extends JpaRepository<User, Long> {
    Optional<User> findByTenantIdAndEmailAndDeletedAtIsNull(Long tenantId, String email);
    Optional<User> findByGoogleIdAndDeletedAtIsNull(String googleId);
    Optional<User> findByTenantIdAndOidcSubjectAndDeletedAtIsNull(Long tenantId, String oidcSubject);
    boolean existsByTenantIdAndEmailAndDeletedAtIsNull(Long tenantId, String email);

    @Modifying
    @Query("UPDATE User u SET u.lastLoginAt = CURRENT_TIMESTAMP WHERE u.id = :id")
    void updateLastLoginAt(Long id);

    /**
     * Compte les utilisateurs non supprimés (soft-delete) de chaque tenant demandé, groupés
     * par {@code tenant_id} — utilisé par {@code SuperAdminTenantService} pour peupler
     * {@code userCount} sur le listing super-admin (US06.2.3), sans compteur dénormalisé.
     *
     * <p>Un tenant sans aucune ligne dans le résultat n'a aucun utilisateur actif — le service
     * appelant doit traiter l'absence comme {@code 0}.
     *
     * @param tenantIds identifiants des tenants pour lesquels compter les utilisateurs
     * @return une projection {@code (tenantId, userCount)} par tenant ayant au moins un
     *     utilisateur non supprimé parmi {@code tenantIds}
     */
    @Query("SELECT u.tenant.id AS tenantId, COUNT(u) AS userCount FROM User u "
            + "WHERE u.tenant.id IN :tenantIds AND u.deletedAt IS NULL GROUP BY u.tenant.id")
    List<TenantUserCountProjection> countActiveUsersByTenantIds(@Param("tenantIds") List<Long> tenantIds);
}

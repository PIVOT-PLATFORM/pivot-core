package fr.pivot.auth.repository;

import fr.pivot.auth.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.List;
import java.util.Optional;

/**
 * Spring Data repository for {@link User}.
 *
 * <p>Extends {@link JpaSpecificationExecutor} to support the dynamic, optional filter
 * combinations (role / status / search) required by {@code GET /api/admin/users}
 * (US06.1.1) — see {@link UserSpecifications}.
 */
public interface UserRepository extends JpaRepository<User, Long>, JpaSpecificationExecutor<User> {
    Optional<User> findByTenantIdAndEmailAndDeletedAtIsNull(Long tenantId, String email);
    Optional<User> findByGoogleIdAndDeletedAtIsNull(String googleId);
    Optional<User> findByTenantIdAndOidcSubjectAndDeletedAtIsNull(Long tenantId, String oidcSubject);
    boolean existsByTenantIdAndEmailAndDeletedAtIsNull(Long tenantId, String email);

    /**
     * Recherche un utilisateur par identifiant, scopé au tenant courant — utilisé par les
     * endpoints d'administration ({@code PATCH /api/admin/users/{userId}/...}, US06.1.3 et
     * suivantes) pour vérifier l'appartenance tenant avant tout traitement. Un {@code id} qui
     * existe mais appartient à un autre tenant, ou qui désigne un compte soft-supprimé, doit
     * renvoyer un résultat vide ici — jamais révéler son existence à l'appelant (404, pas 403).
     *
     * @param id       identifiant technique de l'utilisateur ciblé (path variable, non fiable)
     * @param tenantId identifiant du tenant de l'administrateur appelant, résolu depuis le
     *                 token porteur — jamais depuis le corps ou un paramètre de requête
     * @return l'utilisateur s'il existe, appartient à {@code tenantId} et n'est pas
     *     soft-supprimé, vide sinon
     */
    Optional<User> findByIdAndTenantIdAndDeletedAtIsNull(Long id, Long tenantId);

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

package fr.pivot.auth.repository;

import fr.pivot.auth.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.time.Instant;
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

    /**
     * Existence check ignoring soft-delete state — used to prevent Google/OIDC JIT provisioning
     * from resurrecting a {@code PENDING_DELETION} account under a new row (US02.2.4). The
     * {@code idx_users_tenant_email} unique index is NOT partial (covers every row regardless of
     * {@code deleted_at}), so a naive "no live user with this email" check followed by an insert
     * would otherwise throw a raw {@link org.springframework.dao.DataIntegrityViolationException}
     * instead of a clean 403 for an email still held by a soft-deleted row awaiting purge.
     *
     * @param tenantId the tenant to check within
     * @param email    the candidate email address, already lower-cased by the caller
     * @return {@code true} if any row — deleted or not — already holds this (tenantId, email)
     */
    boolean existsByTenantIdAndEmail(Long tenantId, String email);

    /**
     * Accounts whose grace period has elapsed and are still awaiting anonymization — feeds
     * {@code AccountDeletionScheduler} (US02.2.4, RGPD Art. 17).
     *
     * @param before cutoff instant, normally {@code Instant.now()}
     * @return accounts due for anonymization, unordered
     */
    List<User> findByDeletedAtIsNotNullAndAnonymizedAtIsNullAndScheduledDeletionAtBefore(Instant before);

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

package fr.pivot.auth.repository;

import fr.pivot.auth.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import java.util.Optional;

public interface UserRepository extends JpaRepository<User, Long> {
    Optional<User> findByTenantIdAndEmailAndDeletedAtIsNull(Long tenantId, String email);
    Optional<User> findByGoogleIdAndDeletedAtIsNull(String googleId);
    Optional<User> findByTenantIdAndOidcSubjectAndDeletedAtIsNull(Long tenantId, String oidcSubject);
    boolean existsByTenantIdAndEmailAndDeletedAtIsNull(Long tenantId, String email);

    @Modifying
    @Query("UPDATE User u SET u.lastLoginAt = CURRENT_TIMESTAMP WHERE u.id = :id")
    void updateLastLoginAt(Long id);
}

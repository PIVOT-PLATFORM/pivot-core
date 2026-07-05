package fr.pivot.auth.repository;

import fr.pivot.auth.entity.AuditEvent;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AuditEventRepository extends JpaRepository<AuditEvent, Long> {

    /**
     * Returns all audit events for a user, most recent first — feeds the "audit events" section
     * of the RGPD Art. 20 personal-data export (US02.3.1).
     *
     * <p>{@code user_id} always records the <em>actor</em> of an event (see
     * {@link fr.pivot.account.dto.ExportAuditEventDto}), so this query can only ever return
     * events this user themselves performed — never another user's data.
     *
     * @param userId the export owner
     * @return every audit event where this user is the actor
     */
    List<AuditEvent> findByUserIdOrderByCreatedAtDesc(Long userId);
}

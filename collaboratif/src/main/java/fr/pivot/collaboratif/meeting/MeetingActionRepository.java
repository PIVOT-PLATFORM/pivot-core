package fr.pivot.collaboratif.meeting;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

/**
 * Spring Data repository for {@link MeetingAction} persistence (US12.2.1 AC-08).
 */
public interface MeetingActionRepository extends JpaRepository<MeetingAction, UUID> {
}

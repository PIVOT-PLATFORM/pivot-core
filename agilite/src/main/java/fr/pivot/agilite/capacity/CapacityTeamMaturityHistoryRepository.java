package fr.pivot.agilite.capacity;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

/**
 * Spring Data JPA repository for {@link CapacityTeamMaturityHistory} entities (US11.6.4).
 */
public interface CapacityTeamMaturityHistoryRepository extends JpaRepository<CapacityTeamMaturityHistory, UUID> {
}

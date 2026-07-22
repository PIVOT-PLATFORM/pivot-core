package fr.pivot.agilite.capacity;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Spring Data JPA repository for {@link CapacityBurndownEntry} entities (US11.4.2).
 */
public interface CapacityBurndownEntryRepository extends JpaRepository<CapacityBurndownEntry, UUID> {

    /**
     * Finds the existing entry for a given event and date, if any — the idempotent-upsert AC
     * checks this before deciding insert vs. update.
     *
     * @param eventId the owning event's UUID
     * @param date    the calendar date
     * @return the existing entry, or empty if none was recorded yet for that date
     */
    Optional<CapacityBurndownEntry> findByEventIdAndDate(UUID eventId, LocalDate date);

    /**
     * Lists every entry recorded for an event, ordered by date.
     *
     * @param eventId the owning event's UUID
     * @return the event's burndown entries, ordered by {@code date} ascending
     */
    List<CapacityBurndownEntry> findAllByEventIdOrderByDateAsc(UUID eventId);
}

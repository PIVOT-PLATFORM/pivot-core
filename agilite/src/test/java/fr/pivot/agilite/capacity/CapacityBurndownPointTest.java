package fr.pivot.agilite.capacity;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests unitaires pour l'entité {@link CapacityBurndownPoint} (E11 — capacity planning).
 */
class CapacityBurndownPointTest {

    @Test
    void constructor_shouldSetAllFields() {
        final UUID eventId = UUID.randomUUID();
        final Instant createdAt = Instant.now();

        final CapacityBurndownPoint point = new CapacityBurndownPoint(
                eventId, LocalDate.of(2026, 6, 5), 28.5, createdAt);

        assertThat(point.getId()).isNull();
        assertThat(point.getEventId()).isEqualTo(eventId);
        assertThat(point.getDate()).isEqualTo(LocalDate.of(2026, 6, 5));
        assertThat(point.getPointsRestants()).isEqualTo(28.5);
        assertThat(point.getCreatedAt()).isEqualTo(createdAt);
    }
}

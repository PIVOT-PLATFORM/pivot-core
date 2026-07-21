package fr.pivot.agilite.capacity;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests unitaires pour l'entité {@link CapacityVelocity} (E11 — capacity planning).
 */
class CapacityVelocityTest {

    @Test
    void constructor_shouldSetAllFields() {
        final UUID sprintEventId = UUID.randomUUID();
        final Instant createdAt = Instant.now();

        final CapacityVelocity velocity = new CapacityVelocity(sprintEventId, 40.0, 35.0, createdAt);

        assertThat(velocity.getId()).isNull();
        assertThat(velocity.getSprintEventId()).isEqualTo(sprintEventId);
        assertThat(velocity.getPointsEngages()).isEqualTo(40.0);
        assertThat(velocity.getPointsLivres()).isEqualTo(35.0);
        assertThat(velocity.getCreatedAt()).isEqualTo(createdAt);
    }
}

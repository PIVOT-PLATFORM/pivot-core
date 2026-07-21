package fr.pivot.agilite.capacity;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests unitaires pour l'entité {@link CapacityEvent} (E11 — capacity planning).
 */
class CapacityEventTest {

    private static CapacityEvent newEvent() {
        return new CapacityEvent(
                1L,
                2L,
                CapacityEventType.SPRINT,
                "Sprint 42",
                LocalDate.of(2026, 6, 1),
                LocalDate.of(2026, 6, 12),
                new Integer[] {1, 2, 3, 4, 5});
    }

    @Test
    void constructor_shouldSetCoreFields() {
        final CapacityEvent event = newEvent();

        assertThat(event.getId()).isNull();
        assertThat(event.getTenantId()).isEqualTo(1L);
        assertThat(event.getTeamId()).isEqualTo(2L);
        assertThat(event.getType()).isEqualTo(CapacityEventType.SPRINT);
        assertThat(event.getName()).isEqualTo("Sprint 42");
        assertThat(event.getStartDate()).isEqualTo(LocalDate.of(2026, 6, 1));
        assertThat(event.getEndDate()).isEqualTo(LocalDate.of(2026, 6, 12));
        assertThat(event.getWorkingDays()).containsExactly(1, 2, 3, 4, 5);
    }

    @Test
    void constructor_shouldDefaultStatusToPlanning() {
        final CapacityEvent event = newEvent();

        assertThat(event.getStatus()).isEqualTo(CapacityEventStatus.PLANNING);
    }

    @Test
    void constructor_shouldDefaultIpSprintToFalse() {
        final CapacityEvent event = newEvent();

        assertThat(event.isIpSprint()).isFalse();
    }

    @Test
    void constructor_shouldDefaultOptionalFieldsToNull() {
        final CapacityEvent event = newEvent();

        assertThat(event.getParentId()).isNull();
        assertThat(event.getMaturityLevel()).isNull();
        assertThat(event.getFocusFactor()).isNull();
        assertThat(event.getMargeSecurite()).isNull();
        assertThat(event.getPointsPerDay()).isNull();
        assertThat(event.getCommittedPoints()).isNull();
        assertThat(event.getCompletedPoints()).isNull();
        assertThat(event.getNotes()).isNull();
    }

    @Test
    void setStatus_shouldStoreStatus() {
        final CapacityEvent event = newEvent();

        event.setStatus(CapacityEventStatus.ACTIVE);

        assertThat(event.getStatus()).isEqualTo(CapacityEventStatus.ACTIVE);
    }

    @Test
    void setParentId_shouldAttachToParentPi() {
        final CapacityEvent event = newEvent();
        final java.util.UUID parentId = java.util.UUID.randomUUID();

        event.setParentId(parentId);

        assertThat(event.getParentId()).isEqualTo(parentId);
    }

    @Test
    void setIpSprint_shouldMarkAsInnovationAndPlanning() {
        final CapacityEvent event = newEvent();

        event.setIpSprint(true);

        assertThat(event.isIpSprint()).isTrue();
    }

    @Test
    void setMaturityLevel_shouldStoreLevel() {
        final CapacityEvent event = newEvent();

        event.setMaturityLevel(CapacityMaturityLevel.PERFORMING);

        assertThat(event.getMaturityLevel()).isEqualTo(CapacityMaturityLevel.PERFORMING);
    }

    @Test
    void setFocusFactor_shouldStoreOverride() {
        final CapacityEvent event = newEvent();

        event.setFocusFactor(0.8);

        assertThat(event.getFocusFactor()).isEqualTo(0.8);
    }

    @Test
    void setMargeSecurite_shouldStoreOverride() {
        final CapacityEvent event = newEvent();

        event.setMargeSecurite(0.15);

        assertThat(event.getMargeSecurite()).isEqualTo(0.15);
    }

    @Test
    void setPointsPerDay_shouldStoreOverride() {
        final CapacityEvent event = newEvent();

        event.setPointsPerDay(1.5);

        assertThat(event.getPointsPerDay()).isEqualTo(1.5);
    }

    @Test
    void setCommittedPoints_shouldStorePoints() {
        final CapacityEvent event = newEvent();

        event.setCommittedPoints(50.0);

        assertThat(event.getCommittedPoints()).isEqualTo(50.0);
    }

    @Test
    void setCompletedPoints_shouldStorePoints() {
        final CapacityEvent event = newEvent();

        event.setCompletedPoints(45.0);

        assertThat(event.getCompletedPoints()).isEqualTo(45.0);
    }

    @Test
    void setNotes_shouldStoreNotes() {
        final CapacityEvent event = newEvent();

        event.setNotes("A retenir pour le rétro");

        assertThat(event.getNotes()).isEqualTo("A retenir pour le rétro");
    }

    @Test
    void onCreate_shouldStampCreatedAtAndUpdatedAt() {
        final CapacityEvent event = newEvent();

        assertThat(event.getCreatedAt()).isNull();
        assertThat(event.getUpdatedAt()).isNull();

        event.onCreate();

        assertThat(event.getCreatedAt()).isNotNull();
        assertThat(event.getUpdatedAt()).isNotNull();
        assertThat(event.getCreatedAt()).isEqualTo(event.getUpdatedAt());
    }

    @Test
    void onUpdate_shouldRefreshUpdatedAt() {
        final CapacityEvent event = newEvent();
        event.onCreate();
        final Instant before = event.getUpdatedAt();

        event.onUpdate();

        assertThat(event.getUpdatedAt()).isAfterOrEqualTo(before);
        assertThat(event.getCreatedAt()).isBeforeOrEqualTo(event.getUpdatedAt());
    }
}

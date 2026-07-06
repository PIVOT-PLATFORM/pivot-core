package fr.pivot.plan.entity;

import java.time.Instant;
import java.util.Set;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests unitaires pour l'entité {@link Plan}.
 *
 * <p>Traçabilité US03.3.1 — critère « Entité Plan avec association M-N modules ».
 */
class PlanTest {

    @Test
    void defaults_areSane() {
        final Plan plan = new Plan();

        assertThat(plan.getId()).isNull();
        assertThat(plan.getModuleIds()).isEmpty();
        assertThat(plan.getCreatedAt()).isNotNull();
        assertThat(plan.getUpdatedAt()).isNotNull();
    }

    @Test
    void settersAndGetters_roundTrip() {
        final Plan plan = new Plan();

        plan.setName("Pro");
        plan.setModuleIds(Set.of("whiteboard", "roadmap"));

        assertThat(plan.getName()).isEqualTo("Pro");
        assertThat(plan.getModuleIds()).containsExactlyInAnyOrder("whiteboard", "roadmap");
    }

    @Test
    void onUpdate_shouldRefreshUpdatedAt() {
        final Plan plan = new Plan();
        final Instant before = plan.getUpdatedAt();

        plan.onUpdate();

        assertThat(plan.getUpdatedAt()).isAfterOrEqualTo(before);
        assertThat(plan.getCreatedAt()).isBeforeOrEqualTo(plan.getUpdatedAt());
    }
}

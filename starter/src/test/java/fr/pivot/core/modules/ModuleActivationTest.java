package fr.pivot.core.modules;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests unitaires pour l'entité {@link ModuleActivation}.
 *
 * <p>Traçabilité EN03.1 — critère « Entité ModuleActivation en BDD
 * (tenant_id, module_id, enabled, updated_at) ».
 */
class ModuleActivationTest {

    @Test
    void constructor_shouldDefaultToDisabled() {
        final ModuleActivation activation = new ModuleActivation(1L, "whiteboard");

        assertThat(activation.getId()).isNull();
        assertThat(activation.getTenantId()).isEqualTo(1L);
        assertThat(activation.getModuleId()).isEqualTo("whiteboard");
        assertThat(activation.isEnabled()).isFalse();
        assertThat(activation.getCreatedAt()).isNotNull();
        assertThat(activation.getUpdatedAt()).isNotNull();
    }

    @Test
    void setEnabled_shouldChangeState() {
        final ModuleActivation activation = new ModuleActivation(1L, "quiz");

        activation.setEnabled(true);

        assertThat(activation.isEnabled()).isTrue();
    }

    @Test
    void onUpdate_shouldRefreshUpdatedAt() {
        final ModuleActivation activation = new ModuleActivation(1L, "quiz");
        final Instant before = activation.getUpdatedAt();

        activation.onUpdate();

        assertThat(activation.getUpdatedAt()).isAfterOrEqualTo(before);
        assertThat(activation.getCreatedAt()).isBeforeOrEqualTo(activation.getUpdatedAt());
    }
}

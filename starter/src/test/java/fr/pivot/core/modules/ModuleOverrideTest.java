package fr.pivot.core.modules;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests unitaires pour l'entité {@link ModuleOverride}.
 *
 * <p>Traçabilité US03.3.2 — critère « Override stocké en BDD (table dédiée distincte de
 * {@code module_activations}, tenant_id/module_id/enabled/updated_at) ».
 */
class ModuleOverrideTest {

    @Test
    void constructor_shouldPersistExplicitEnabledValue_true() {
        final ModuleOverride override = new ModuleOverride(1L, "whiteboard", true);

        assertThat(override.getId()).isNull();
        assertThat(override.getTenantId()).isEqualTo(1L);
        assertThat(override.getModuleId()).isEqualTo("whiteboard");
        assertThat(override.isEnabled()).isTrue();
        assertThat(override.getCreatedAt()).isNotNull();
        assertThat(override.getUpdatedAt()).isNotNull();
    }

    @Test
    void constructor_shouldPersistExplicitEnabledValue_false() {
        // Contrairement à ModuleActivation, il n'y a pas de valeur par défaut implicite :
        // chaque override est créé avec une valeur assumée par le super admin.
        final ModuleOverride override = new ModuleOverride(1L, "whiteboard", false);

        assertThat(override.isEnabled()).isFalse();
    }

    @Test
    void setEnabled_shouldChangeState() {
        final ModuleOverride override = new ModuleOverride(1L, "quiz", true);

        override.setEnabled(false);

        assertThat(override.isEnabled()).isFalse();
    }

    @Test
    void onUpdate_shouldRefreshUpdatedAt() {
        final ModuleOverride override = new ModuleOverride(1L, "quiz", true);
        final Instant before = override.getUpdatedAt();

        override.onUpdate();

        assertThat(override.getUpdatedAt()).isAfterOrEqualTo(before);
        assertThat(override.getCreatedAt()).isBeforeOrEqualTo(override.getUpdatedAt());
    }
}

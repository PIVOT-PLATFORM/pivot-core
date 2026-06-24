package fr.pivot.auth.entity;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link FeatureFlag} accessors and the {@code enabled}/{@code value}
 * synchronization logic for bool-type flags.
 */
class FeatureFlagTest {

    @Test
    void setEnabled_syncsValueAndTouchesTimestamp() {
        final FeatureFlag flag = new FeatureFlag();

        flag.setEnabled(true);

        assertThat(flag.isEnabled()).isTrue();
        assertThat(flag.getValue()).isEqualTo("true");
        assertThat(flag.getUpdatedAt()).isNotNull();
    }

    @Test
    void setValue_updatesEnabled_forBoolType() {
        final FeatureFlag flag = new FeatureFlag();

        flag.setValue("true");
        assertThat(flag.isEnabled()).isTrue();

        flag.setValue("false");
        assertThat(flag.isEnabled()).isFalse();
        assertThat(flag.getValue()).isEqualTo("false");
    }

    @Test
    void defaults_areBoolTypeAndDisabled() {
        final FeatureFlag flag = new FeatureFlag();

        assertThat(flag.getType()).isEqualTo("bool");
        assertThat(flag.isEnabled()).isFalse();
        assertThat(flag.getId()).isNull();
        assertThat(flag.getFlagKey()).isNull();
        assertThat(flag.getLabel()).isNull();
        assertThat(flag.getDescription()).isNull();
    }

    @Test
    void setUpdatedBy_isStored() {
        final FeatureFlag flag = new FeatureFlag();
        final User admin = new User();

        flag.setUpdatedBy(admin);

        assertThat(flag.getUpdatedBy()).isSameAs(admin);
    }
}

package fr.pivot.agilite.capacity.dto;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests unitaires pour le record {@link MaturityProfile} (E11 — capacity planning).
 */
class MaturityProfileTest {

    @Test
    void accessors_shouldReturnConstructedValues() {
        final MaturityProfile profile = new MaturityProfile(0.7, 0.2, 0.9);

        assertThat(profile.focusFactor()).isEqualTo(0.7);
        assertThat(profile.margin()).isEqualTo(0.2);
        assertThat(profile.velocityMultiplier()).isEqualTo(0.9);
    }
}

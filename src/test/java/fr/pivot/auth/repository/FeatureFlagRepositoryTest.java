package fr.pivot.auth.repository;

import fr.pivot.auth.entity.FeatureFlag;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link FeatureFlagRepository} default typed accessors.
 * Tests the default method logic without Spring context (mock approach).
 */
class FeatureFlagRepositoryTest {

    private final FeatureFlagRepository repo = mock(FeatureFlagRepository.class);

    // ----------------------------------------------------------------
    // getBool()
    // ----------------------------------------------------------------

    @Test
    void getBool_returnsEnabled_whenFlagExists() {
        when(repo.findByFlagKey("MY_FLAG")).thenReturn(Optional.of(boolFlag(true)));
        when(repo.getBool("MY_FLAG", false)).thenCallRealMethod();
        assertThat(repo.getBool("MY_FLAG", false)).isTrue();
    }

    @Test
    void getBool_returnsDefault_whenFlagMissing() {
        when(repo.findByFlagKey("MISSING")).thenReturn(Optional.empty());
        when(repo.getBool("MISSING", true)).thenCallRealMethod();
        assertThat(repo.getBool("MISSING", true)).isTrue();
    }

    // ----------------------------------------------------------------
    // getInt()
    // ----------------------------------------------------------------

    @Test
    void getInt_parsesValue_whenFlagExists() {
        when(repo.findByFlagKey("SESSION_TTL_SECONDS")).thenReturn(Optional.of(intFlag("86400")));
        when(repo.getInt("SESSION_TTL_SECONDS", 3600)).thenCallRealMethod();
        assertThat(repo.getInt("SESSION_TTL_SECONDS", 3600)).isEqualTo(86400);
    }

    @Test
    void getInt_returnsDefault_whenFlagMissing() {
        when(repo.findByFlagKey("MISSING")).thenReturn(Optional.empty());
        when(repo.getInt("MISSING", 999)).thenCallRealMethod();
        assertThat(repo.getInt("MISSING", 999)).isEqualTo(999);
    }

    @Test
    void getInt_returnsDefault_whenValueUnparseable() {
        when(repo.findByFlagKey("BAD_INT")).thenReturn(Optional.of(intFlag("not-a-number")));
        when(repo.getInt("BAD_INT", 42)).thenCallRealMethod();
        assertThat(repo.getInt("BAD_INT", 42)).isEqualTo(42);
    }

    // ----------------------------------------------------------------
    // getFloat()
    // ----------------------------------------------------------------

    @Test
    void getFloat_parsesValue_whenFlagExists() {
        when(repo.findByFlagKey("SESSION_REFRESH_THRESHOLD")).thenReturn(Optional.of(floatFlag("0.5")));
        when(repo.getFloat("SESSION_REFRESH_THRESHOLD", 0.25)).thenCallRealMethod();
        assertThat(repo.getFloat("SESSION_REFRESH_THRESHOLD", 0.25)).isEqualTo(0.5);
    }

    @Test
    void getFloat_returnsDefault_whenValueUnparseable() {
        when(repo.findByFlagKey("BAD_FLOAT")).thenReturn(Optional.of(floatFlag("NaN-value")));
        when(repo.getFloat("BAD_FLOAT", 0.75)).thenCallRealMethod();
        assertThat(repo.getFloat("BAD_FLOAT", 0.75)).isEqualTo(0.75);
    }

    // ----------------------------------------------------------------
    // Helpers
    // ----------------------------------------------------------------

    private FeatureFlag boolFlag(final boolean value) {
        final FeatureFlag f = new FeatureFlag();
        f.setEnabled(value);
        return f;
    }

    private FeatureFlag intFlag(final String value) {
        final FeatureFlag f = new FeatureFlag();
        // Bypass setValue (which checks type) — set value directly via reflection would be needed
        // Using a subclass approach not needed — test covers getInt() default method only
        try {
            final var field = FeatureFlag.class.getDeclaredField("value");
            field.setAccessible(true);
            field.set(f, value);
            final var typeField = FeatureFlag.class.getDeclaredField("type");
            typeField.setAccessible(true);
            typeField.set(f, "int");
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        return f;
    }

    private FeatureFlag floatFlag(final String value) {
        final FeatureFlag f = new FeatureFlag();
        try {
            final var field = FeatureFlag.class.getDeclaredField("value");
            field.setAccessible(true);
            field.set(f, value);
            final var typeField = FeatureFlag.class.getDeclaredField("type");
            typeField.setAccessible(true);
            typeField.set(f, "float");
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        return f;
    }
}

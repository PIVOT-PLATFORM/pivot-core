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

    private FeatureFlag stringFlag(final String value) {
        final FeatureFlag f = new FeatureFlag();
        try {
            final var field = FeatureFlag.class.getDeclaredField("value");
            field.setAccessible(true);
            field.set(f, value);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        return f;
    }

    // ----------------------------------------------------------------
    // getString()
    // ----------------------------------------------------------------

    @Test
    void getString_returnsValue_whenFlagExists() {
        when(repo.findByFlagKey("SUPPORT_EMAIL")).thenReturn(Optional.of(stringFlag("support@pivot.app")));
        when(repo.getString("SUPPORT_EMAIL", "default@pivot.app")).thenCallRealMethod();
        assertThat(repo.getString("SUPPORT_EMAIL", "default@pivot.app")).isEqualTo("support@pivot.app");
    }

    @Test
    void getString_returnsDefault_whenFlagMissing() {
        when(repo.findByFlagKey("MISSING_STR")).thenReturn(Optional.empty());
        when(repo.getString("MISSING_STR", "fallback")).thenCallRealMethod();
        assertThat(repo.getString("MISSING_STR", "fallback")).isEqualTo("fallback");
    }

    @Test
    void getString_returnsDefault_whenValueIsBlank() {
        when(repo.findByFlagKey("BLANK_STR")).thenReturn(Optional.of(stringFlag("   ")));
        when(repo.getString("BLANK_STR", "fallback")).thenCallRealMethod();
        assertThat(repo.getString("BLANK_STR", "fallback")).isEqualTo("fallback");
    }

    // ----------------------------------------------------------------
    // isEnabled()
    // ----------------------------------------------------------------

    @Test
    void isEnabled_returnsTrue_whenFlagExistsAndEnabled() {
        when(repo.findByFlagKey("MY_FEATURE")).thenReturn(Optional.of(boolFlag(true)));
        when(repo.getBool("MY_FEATURE", false)).thenCallRealMethod();
        when(repo.isEnabled("MY_FEATURE")).thenCallRealMethod();
        assertThat(repo.isEnabled("MY_FEATURE")).isTrue();
    }

    @Test
    void isEnabled_returnsFalse_whenFlagMissing() {
        when(repo.findByFlagKey("MISSING_FEATURE")).thenReturn(Optional.empty());
        when(repo.getBool("MISSING_FEATURE", false)).thenCallRealMethod();
        when(repo.isEnabled("MISSING_FEATURE")).thenCallRealMethod();
        assertThat(repo.isEnabled("MISSING_FEATURE")).isFalse();
    }
}

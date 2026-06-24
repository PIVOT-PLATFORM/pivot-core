package fr.pivot.auth.repository;

import fr.pivot.auth.entity.FeatureFlag;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;

/**
 * Repository for {@link FeatureFlag} — runtime-configurable feature toggles.
 *
 * <p>Provides typed accessors for bool, int and float flags to avoid
 * manual parsing at call sites.
 */
public interface FeatureFlagRepository extends JpaRepository<FeatureFlag, Long> {

    /**
     * Finds a feature flag by its unique key.
     *
     * @param flagKey the unique flag identifier (e.g. "MFA_NEW_DEVICE_OTP")
     * @return matching flag, or empty if not found
     */
    Optional<FeatureFlag> findByFlagKey(String flagKey);

    /**
     * Returns the boolean state of a flag.
     *
     * <p>Falls back to {@code defaultValue} when the flag is not found.
     *
     * @param flagKey      unique flag key
     * @param defaultValue value returned when flag is absent
     * @return flag value as boolean
     */
    default boolean getBool(final String flagKey, final boolean defaultValue) {
        return findByFlagKey(flagKey)
            .map(FeatureFlag::isEnabled)
            .orElse(defaultValue);
    }

    /**
     * Returns the integer value of a typed flag.
     *
     * <p>Falls back to {@code defaultValue} when the flag is not found or unparseable.
     *
     * @param flagKey      unique flag key
     * @param defaultValue value returned when flag is absent
     * @return flag value as int
     */
    default int getInt(final String flagKey, final int defaultValue) {
        return findByFlagKey(flagKey).map(f -> {
            try {
                return Integer.parseInt(f.getValue());
            } catch (NumberFormatException _) {
                return defaultValue;
            }
        }).orElse(defaultValue);
    }

    /**
     * Returns the float value of a typed flag.
     *
     * <p>Falls back to {@code defaultValue} when the flag is not found or unparseable.
     *
     * @param flagKey      unique flag key
     * @param defaultValue value returned when flag is absent
     * @return flag value as double
     */
    default double getFloat(final String flagKey, final double defaultValue) {
        return findByFlagKey(flagKey).map(f -> {
            try {
                return Double.parseDouble(f.getValue());
            } catch (NumberFormatException _) {
                return defaultValue;
            }
        }).orElse(defaultValue);
    }

    /**
     * Checks if a boolean flag is enabled.
     *
     * <p>Backward-compatible convenience — callers can migrate to {@link #getBool} gradually.
     *
     * @param flagKey unique flag key
     * @return {@code true} if the flag exists and is enabled; {@code false} otherwise
     */
    default boolean isEnabled(final String flagKey) {
        return getBool(flagKey, false);
    }
}

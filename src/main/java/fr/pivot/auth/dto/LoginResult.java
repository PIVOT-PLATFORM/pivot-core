package fr.pivot.auth.dto;

/**
 * Result of a login or device-OTP verification attempt.
 *
 * <p>Two exclusive states:
 * <ul>
 *   <li>{@code requiresDeviceVerification = true} — MFA pending; token fields are {@code null}.</li>
 *   <li>{@code requiresDeviceVerification = false} — authentication complete; all token fields
 *       are populated.</li>
 * </ul>
 *
 * <p>From US-AUTH-002: {@code sessionToken} is the raw opaque token (UUID) sent to the client
 * as both the {@code Authorization: Bearer} value and the session cookie value.
 * {@code sessionTtlSeconds} is used by the controller to set the cookie {@code Max-Age}.
 */
public record LoginResult(
    boolean requiresDeviceVerification,
    String pendingDeviceFingerprint,
    String sessionToken,
    long expiresAt,
    long sessionTtlSeconds,
    AuthResponse.UserInfo user
) {

    /**
     * Creates a successful login result with an issued opaque session token.
     *
     * @param sessionToken     raw opaque token (UUID) — must be sent as Bearer + set in cookie
     * @param expiresAt        epoch-millisecond timestamp of token expiry
     * @param sessionTtlSeconds token TTL in seconds, used for cookie {@code Max-Age}
     * @param user             authenticated user information
     */
    public static LoginResult success(
            final String sessionToken,
            final long expiresAt,
            final long sessionTtlSeconds,
            final AuthResponse.UserInfo user) {
        return new LoginResult(false, null, sessionToken, expiresAt, sessionTtlSeconds, user);
    }

    /**
     * Creates a pending result indicating that device OTP verification is required.
     *
     * @param deviceFingerprint the device fingerprint awaiting OTP confirmation
     */
    public static LoginResult requiresDeviceVerification(final String deviceFingerprint) {
        return new LoginResult(true, deviceFingerprint, null, 0L, 0L, null);
    }
}

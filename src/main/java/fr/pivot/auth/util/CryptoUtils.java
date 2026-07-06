package fr.pivot.auth.util;

import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Stateless crypto primitives shared across auth services.
 *
 * <p>All methods are pure functions — no state, no injection required.
 * Instantiation is forbidden; use static methods directly.
 */
public final class CryptoUtils {

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private CryptoUtils() {}

    /**
     * Returns the lowercase hex-encoded SHA-256 digest of {@code input}.
     *
     * <p>Used to store token hashes in the database — raw tokens are never persisted.
     *
     * @param input the plaintext token or value to hash
     * @return 64-character hex string
     */
    public static String sha256(final String input) {
        try {
            final MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(input.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    /**
     * Returns the lowercase hex-encoded HMAC-SHA256 of {@code message} keyed by {@code secretKey}.
     *
     * <p>Used for short low-entropy secrets (e.g. 6-digit OTP): unlike a bare SHA-256, an
     * attacker who exfiltrates the database cannot brute-force the value offline without also
     * knowing the server-side {@code secretKey}.
     *
     * @param message   the value to authenticate (e.g. the OTP)
     * @param secretKey the server-side HMAC key
     * @return 64-character hex string
     */
    public static String hmacSha256(final String message, final String secretKey) {
        try {
            final Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secretKey.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(message.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new IllegalStateException("HmacSHA256 not available", e);
        }
    }

    /**
     * Generates a 256-bit cryptographically secure URL-safe token (no padding).
     *
     * <p>Suitable for email verification, password reset, and device verification tokens.
     *
     * @return 43-character base64url-encoded random token
     */
    public static String generateSecureToken() {
        final byte[] bytes = new byte[32];
        SECURE_RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /**
     * Resolves the HMAC key used to hash short low-entropy OTP codes (device verification,
     * account-deletion confirmation): the configured {@code pivot.auth.otp-secret} value, or a
     * fresh ephemeral one when unset.
     *
     * <p>Extracted so every OTP-issuing service (currently {@code SessionService} and
     * {@code AccountDeletionService}) shares the exact same ephemeral-fallback behaviour instead
     * of re-implementing it: without a configured secret, OTPs hashed with the ephemeral key stop
     * verifying after a restart (acceptable in dev — TTLs are a few minutes). Set
     * {@code PIVOT_AUTH_OTP_SECRET} in production for a stable key across instances/restarts.
     * Each calling service still logs its own warning so the responsible bean is identifiable.
     *
     * @param configured the raw {@code pivot.auth.otp-secret} property value, possibly blank
     * @return {@code configured} unchanged if non-blank, otherwise a fresh secure random secret
     */
    public static String resolveOtpSecret(final String configured) {
        if (configured == null || configured.isBlank()) {
            return generateSecureToken();
        }
        return configured;
    }
}

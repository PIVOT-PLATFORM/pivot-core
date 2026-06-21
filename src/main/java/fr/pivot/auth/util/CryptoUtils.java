package fr.pivot.auth.util;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

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
}

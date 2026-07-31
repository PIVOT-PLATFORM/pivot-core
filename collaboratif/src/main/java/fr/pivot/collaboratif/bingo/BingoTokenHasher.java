package fr.pivot.collaboratif.bingo;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Hashes an opaque Bingo accessToken into its {@code participant_key} form (US47.1.1, SEC-03):
 * hex-encoded SHA-256. The raw token itself is never persisted anywhere — only this digest,
 * used to resolve a participant's own {@link BingoGrid} (never a client-supplied identifier,
 * SEC-02).
 */
public final class BingoTokenHasher {

    private static final String ALGORITHM = "SHA-256";

    private BingoTokenHasher() {
    }

    /**
     * Hashes the given raw access token.
     *
     * @param accessToken the raw, opaque access token
     * @return the hex-encoded SHA-256 digest (64 characters)
     */
    public static String hash(final String accessToken) {
        try {
            MessageDigest digest = MessageDigest.getInstance(ALGORITHM);
            byte[] bytes = digest.digest(accessToken.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(bytes);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(ALGORITHM + " unavailable", e);
        }
    }
}

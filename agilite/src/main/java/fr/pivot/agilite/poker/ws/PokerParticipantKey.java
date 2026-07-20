package fr.pivot.agilite.poker.ws;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Derives the stable, non-reversible participant key used to identify a planning poker
 * participant across the roster and their votes (E09).
 *
 * <p>The key is the SHA-256 hex digest of the participant's room access token — never the raw
 * token. Both the roster ({@link PokerParticipantRegistryService}) and the vote store ({@code
 * PokerVoteService}/{@code agilite.poker_votes}) key on this exact value, so a participant's
 * roster entry and their vote correlate without ever persisting or broadcasting the token itself
 * (defense-in-depth: a leak of either store hands out no usable token). Centralised here so the
 * two sides can never drift to different hashing.
 */
public final class PokerParticipantKey {

    private static final String HASH_ALGORITHM = "SHA-256";

    private PokerParticipantKey() {
    }

    /**
     * Computes the participant key for a raw room access token.
     *
     * @param accessToken the raw access token (never stored)
     * @return the lowercase hex-encoded SHA-256 digest of {@code accessToken}
     */
    public static String of(final String accessToken) {
        try {
            MessageDigest digest = MessageDigest.getInstance(HASH_ALGORITHM);
            byte[] hashed = digest.digest(accessToken.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hashed);
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is a JLS-mandated standard algorithm — unreachable at runtime.
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}

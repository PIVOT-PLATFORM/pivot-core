package fr.pivot.collaboratif.bingo;

import java.security.SecureRandom;

/**
 * Generates 6-character invite codes for Bingo rooms (US47.1.1) — declines {@code
 * fr.pivot.agilite.poker.InviteCodeGenerator}'s exact alphabet/shape inside this module (no
 * inter-module dependency is possible, ADR-006), same alphabet choice for the same reason:
 * excludes visually ambiguous characters ({@code 0}/{@code O}, {@code 1}/{@code I}) since a code
 * is typically read aloud or copy-pasted from a screen share.
 */
public final class BingoInviteCodeGenerator {

    /** Alphabet excluding {@code 0}/{@code O} and {@code 1}/{@code I} to avoid ambiguous typos. */
    static final String ALPHABET = "23456789ABCDEFGHJKLMNPQRSTUVWXYZ";

    private static final int CODE_LENGTH = 6;

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private BingoInviteCodeGenerator() {
    }

    /**
     * Generates a random 6-character invite code from {@link #ALPHABET}.
     *
     * @return a freshly generated invite code (not guaranteed unique — the caller must check)
     */
    public static String generate() {
        final StringBuilder builder = new StringBuilder(CODE_LENGTH);
        for (int i = 0; i < CODE_LENGTH; i++) {
            builder.append(ALPHABET.charAt(SECURE_RANDOM.nextInt(ALPHABET.length())));
        }
        return builder.toString();
    }
}

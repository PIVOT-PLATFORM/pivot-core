package fr.pivot.account.util;

/**
 * Strips HTML markup from user-supplied text fields (US02.1.1 — XSS protection on
 * profile {@code firstName}/{@code lastName}, displayed to other users e.g. in the
 * admin user list, US06.1.x).
 *
 * <p>This is a <strong>stripping</strong> utility, not a rejection/validation one: tags are
 * removed and the surrounding text content is kept (e.g. {@code "<b>Bob</b>"} → {@code "Bob"}),
 * mirroring the semantics of PHP's {@code strip_tags}. Angular renders the stored value via
 * interpolation ({@code {{ value }}}), which HTML-escapes on output — stripping at write time
 * is defense-in-depth against stored payloads (logs, exports, future non-Angular consumers),
 * not the only XSS control.
 *
 * <p><strong>Implementation note:</strong> deliberately hand-rolled as a single linear scan
 * instead of a {@code Pattern}-based {@code <[^>]*>} replace. That regex is safe in isolation,
 * but on a string with many {@code '<'} characters and no closing {@code '>'} it degrades to
 * quadratic time (CodeQL: "polynomial regular expression used on uncontrolled data") because
 * {@code Matcher} retries the failed match at every subsequent position. This method is a
 * public, reusable utility not tied to the caller-enforced 100-character cap on name fields, so
 * it must be safe (O(n)) for arbitrary-length, adversarial input on its own.
 */
public final class HtmlStripper {

    private HtmlStripper() {
    }

    /**
     * Removes HTML tags from the given text, keeping the inner text content.
     *
     * <p>After removing well-formed {@code <tag>} occurrences, any residual unmatched
     * {@code <} or {@code >} character (e.g. an unclosed {@code "<img src=x"}) is also
     * stripped — defense-in-depth so a malformed/truncated tag cannot survive.
     *
     * @param input raw text, possibly {@code null}
     * @return {@code input} with all HTML markup removed, or {@code null} if {@code input} was {@code null}
     */
    public static String stripTags(final String input) {
        if (input == null) {
            return null;
        }
        final int length = input.length();

        // nextClosingBracket[i] = index of the first '>' at or after position i, or `length`
        // if none exists — precomputed with one backward pass so the forward scan below can
        // jump straight past a well-formed <...> span in O(1) instead of re-scanning ahead for
        // its closing '>' (which is what makes the naive regex quadratic on pathological input).
        final int[] nextClosingBracket = new int[length + 1];
        nextClosingBracket[length] = length;
        for (int i = length - 1; i >= 0; i--) {
            nextClosingBracket[i] = input.charAt(i) == '>' ? i : nextClosingBracket[i + 1];
        }

        final StringBuilder result = new StringBuilder(length);
        int i = 0;
        while (i < length) {
            final char c = input.charAt(i);
            if (c == '<') {
                final int closingBracket = nextClosingBracket[i + 1];
                // Well-formed <...> span found ahead: drop the whole thing in one jump.
                // Otherwise (no more '>' anywhere after this '<'): it's a stray, unmatched
                // bracket — drop just this one character and keep scanning normally.
                i = closingBracket < length ? closingBracket + 1 : i + 1;
            } else if (c == '>') {
                // Stray '>' not consumed as the end of a tag above — drop it too.
                i++;
            } else {
                result.append(c);
                i++;
            }
        }
        return result.toString();
    }
}

package fr.pivot.account.util;

import java.util.regex.Pattern;

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
 */
public final class HtmlStripper {

    /** Matches a complete {@code <...>} tag, opening or closing. */
    private static final Pattern TAG_PATTERN = Pattern.compile("<[^>]*>");

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
        final String withoutTags = TAG_PATTERN.matcher(input).replaceAll("");
        return withoutTags.replace("<", "").replace(">", "");
    }
}

package fr.pivot.auth.util;

import java.util.regex.Pattern;

/**
 * Strips HTML markup and enforces a maximum length on free-text values supplied by the
 * client before they are persisted (US02.2.3 — «appareil» field on {@code access_tokens}).
 *
 * <p>The device label ({@code deviceName}) is client-supplied (browser fingerprinting JS,
 * {@code User-Agent}-derived heuristics) and later rendered in the "active sessions" screen.
 * Angular renders it via text binding (never {@code innerHTML}), but the raw value is also
 * stored in the database and could be consumed by other surfaces (exports, admin tooling,
 * future API clients) — stripping any {@code <tag>} content at write time is defence in depth
 * against stored XSS, independent of how any given renderer behaves.
 *
 * <p>Stateless pure functions — instantiation is forbidden.
 */
public final class HtmlStripper {

    /** Matches any HTML/XML tag, opening or closing, including self-closing ones. */
    private static final Pattern TAG_PATTERN = Pattern.compile("<[^>]*>");

    private HtmlStripper() {
    }

    /**
     * Removes HTML tags and truncates the result to {@code maxLength} characters.
     *
     * <p>Only the tags themselves are removed — text content between tags is preserved
     * (e.g. {@code "<b>Chrome</b>"} becomes {@code "Chrome"}). The result is trimmed of
     * leading/trailing whitespace before truncation.
     *
     * <p>{@link #TAG_PATTERN} only matches well-formed {@code <...>} spans, so a value with an
     * <em>unterminated</em> tag (e.g. {@code "Chrome<img src=x onerror=alert(1)"}, missing the
     * closing {@code >}) would otherwise pass through untouched. Any {@code <} or {@code >}
     * character still present after that pass is stripped as well, so the result is guaranteed
     * free of angle brackets regardless of how malformed the input markup is.
     *
     * @param value     raw client-supplied value, may be {@code null}
     * @param maxLength maximum number of characters to keep (after stripping)
     * @return the stripped and truncated value, or {@code null} if {@code value} was {@code null}
     */
    public static String stripAndTruncate(final String value, final int maxLength) {
        if (value == null) {
            return null;
        }
        final String withoutTags = TAG_PATTERN.matcher(value).replaceAll("");
        final String stripped = withoutTags.replace("<", "").replace(">", "").trim();
        return stripped.length() > maxLength ? stripped.substring(0, maxLength) : stripped;
    }
}

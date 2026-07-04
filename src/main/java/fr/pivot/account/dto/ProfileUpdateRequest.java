package fr.pivot.account.dto;

/**
 * Payload for {@code PATCH /account/profile} (US02.1.1) — updates {@code firstName} and
 * {@code lastName} only.
 *
 * <p><strong>Security — email change out of scope (US02.2.2):</strong> this record is built by
 * {@code AccountController} from a raw {@code Map<String, Object>} request body, not deserialized
 * directly by Jackson. This is deliberate: Jackson 3.x (used by Spring Boot 4.x here) defaults
 * {@code DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES} to {@code false} — unlike Jackson 2.x
 * — so a per-class {@code @JsonIgnoreProperties(ignoreUnknown = false)} annotation alone does
 * <em>not</em> make deserialization fail on an unexpected property (confirmed empirically; the
 * class-level flag only avoids forcing lenience, it cannot force strictness once the global
 * feature is disabled). Reading a {@code Map} first lets the controller explicitly detect and
 * reject an {@code email} key with a clear {@code 400}, independent of that global Jackson
 * default. Email changes are handled by a dedicated flow (US02.2.2) that will require
 * re-verification — this endpoint must never silently accept or ignore an {@code email} field.
 *
 * <p>Presence/length validation and HTML stripping (a value can strip down to blank, e.g.
 * {@code "<script></script>"}) all happen in {@code ProfileService}.
 *
 * @param firstName new first name, as read from the request body (not yet validated/stripped)
 * @param lastName  new last name, as read from the request body (not yet validated/stripped)
 */
public record ProfileUpdateRequest(String firstName, String lastName) {
}

package fr.pivot.auth.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;

/**
 * A single active session exposed by {@code GET /api/account/sessions} (US02.2.3).
 *
 * <p>Never the {@link fr.pivot.auth.entity.AccessToken} entity directly — {@code tokenHash}
 * must never leave the backend. {@code device} is already HTML-stripped and truncated to
 * 200 characters at write time (see {@link fr.pivot.auth.util.HtmlStripper}); the frontend
 * must still render it as text content, never {@code innerHTML}, as defence in depth.
 *
 * @param id        the {@link fr.pivot.auth.entity.AccessToken} primary key — used as the path
 *                  variable for {@code DELETE /api/account/sessions/{tokenId}}
 * @param device    human-readable device label, or {@code null} if none was captured at login
 * @param ip        the IP address the session was created from
 * @param createdAt when the session was created
 * @param expiresAt when the session will expire if not revoked first
 * @param isCurrent {@code true} if this is the session backing the current request
 */
public record SessionDto(
    Long id,
    String device,
    String ip,
    Instant createdAt,
    Instant expiresAt,
    @JsonProperty("isCurrent") boolean isCurrent) {
}

package fr.pivot.auth.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;

/**
 * A single trusted device exposed by {@code GET /api/auth/devices} (US01.4.2).
 *
 * <p>Never the {@link fr.pivot.auth.entity.TrustedDevice} entity directly — {@code deviceFingerprint}
 * must never leave the backend. {@code device} is HTML-stripped and truncated to 200 characters at
 * mapping time (see {@link fr.pivot.auth.util.HtmlStripper}); the frontend must still render it as
 * text content, never {@code innerHTML}, as defence in depth.
 *
 * @param id         the {@link fr.pivot.auth.entity.TrustedDevice} primary key — used as the path
 *                   variable for {@code DELETE /api/auth/devices/{deviceId}}
 * @param device     human-readable device label, or {@code null} if none was captured
 * @param ip         the IP address the device was trusted from
 * @param createdAt  when the device became trusted (maps from {@code confirmedAt})
 * @param lastSeenAt when the device was last seen active
 * @param isCurrent  {@code true} if this is the device backing the current session
 */
public record TrustedDeviceDto(
    Long id,
    String device,
    String ip,
    Instant createdAt,
    Instant lastSeenAt,
    @JsonProperty("isCurrent") boolean isCurrent) {
}

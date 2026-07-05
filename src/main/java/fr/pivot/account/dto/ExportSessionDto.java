package fr.pivot.account.dto;

import fr.pivot.auth.entity.AccessToken;

import java.time.Instant;

/**
 * "sessions" section of the RGPD Art. 20 data export (US02.3.1).
 *
 * <p>Sourced from {@link AccessToken} rows scoped to the export owner only. Never includes
 * {@code tokenHash} (credential material) or {@code deviceFingerprint} (a technical value with
 * no meaning to the end user).
 */
public record ExportSessionDto(
    String deviceName,
    String userAgent,
    String ipAddress,
    String authMethod,
    boolean rememberMe,
    String status,
    Instant createdAt,
    Instant lastUsedAt,
    Instant expiresAt
) {

    /**
     * Builds the DTO from a session's {@link AccessToken}.
     *
     * @param token an access token owned by the export subject
     * @return the corresponding session entry
     */
    public static ExportSessionDto from(final AccessToken token) {
        return new ExportSessionDto(
            token.getDeviceName(),
            token.getUserAgent(),
            token.getIpAddress(),
            token.getAuthMethod() != null ? token.getAuthMethod().name() : null,
            token.isRememberMe(),
            token.getStatus() != null ? token.getStatus().name() : null,
            token.getCreatedAt(),
            token.getLastUsedAt(),
            token.getExpiresAt());
    }
}

package fr.pivot.account.dto;

import fr.pivot.auth.entity.User;

import java.time.Instant;

/**
 * "profil" section of the RGPD Art. 20 data export (US02.3.1).
 *
 * <p>Deliberately excludes {@code passwordHash}, {@code googleId} and {@code oidcSubject} —
 * internal credential/link material, not data the export is meant to surface to the user.
 */
public record ExportProfileDto(
    Long id,
    String email,
    String firstName,
    String lastName,
    String role,
    String locale,
    boolean emailVerified,
    String avatarUrl,
    String tenantName,
    Instant createdAt,
    Instant lastLoginAt
) {

    /**
     * Builds the DTO from the owning {@link User} entity.
     *
     * @param user the export owner (never another user — enforced by the caller)
     * @return the profile section of the export
     */
    public static ExportProfileDto from(final User user) {
        return new ExportProfileDto(
            user.getId(),
            user.getEmail(),
            user.getFirstName(),
            user.getLastName(),
            user.getRole(),
            user.getLocale(),
            user.isEmailVerified(),
            user.getAvatarUrl(),
            user.getTenant() != null ? user.getTenant().getName() : null,
            user.getCreatedAt(),
            user.getLastLoginAt());
    }
}

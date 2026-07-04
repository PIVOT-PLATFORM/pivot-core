package fr.pivot.account.dto;

import fr.pivot.auth.entity.User;

/**
 * API-safe representation of the current user's account profile (US02.1.1, US02.1.2).
 *
 * <p>Returned by {@code GET /account/profile}, {@code PATCH /account/profile} and the avatar
 * upload endpoint. Never exposes the JPA {@link User} entity directly, per architectural
 * contract.
 *
 * @param firstName         first name, or {@code null} if never set
 * @param lastName          last name, or {@code null} if never set
 * @param email             account email address
 * @param avatarUrl         URL of the profile picture, or {@code null} if none is set — the
 *                          frontend falls back to an initials avatar in that case
 * @param preferredLanguage preferred UI language — always {@code "fr"} or {@code "en"} (never
 *                          {@code null}: defaults to {@code "fr"} at registration). Also
 *                          surfaced on login in {@code AuthResponse.UserInfo} (see
 *                          {@code UserMapper}) so the frontend can apply it before the user
 *                          ever visits the profile page.
 */
public record ProfileDto(
        String firstName, String lastName, String email, String avatarUrl, String preferredLanguage) {

    /**
     * Maps a {@link User} entity to its {@link ProfileDto} representation.
     *
     * @param user the authenticated user entity
     * @return DTO safe for serialization in API responses
     */
    public static ProfileDto from(final User user) {
        return new ProfileDto(
                user.getFirstName(), user.getLastName(), user.getEmail(), user.getAvatarUrl(), user.getLocale());
    }
}

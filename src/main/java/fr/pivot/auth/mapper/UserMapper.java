package fr.pivot.auth.mapper;

import fr.pivot.auth.dto.AuthResponse;
import fr.pivot.auth.entity.User;

/**
 * Converts {@link User} entity to API-safe DTOs.
 *
 * <p>Keeps entity details out of the transport layer — controllers and services
 * never expose JPA entities directly per architectural contract.
 */
public final class UserMapper {

    private UserMapper() {}

    /**
     * Maps a {@link User} entity to its {@link AuthResponse.UserInfo} representation.
     *
     * <p>Caller must ensure {@code user.getTenant()} is initialized (not a lazy proxy).
     *
     * @param user the authenticated user entity
     * @return DTO safe for serialization in API responses
     */
    public static AuthResponse.UserInfo toUserInfo(final User user) {
        return new AuthResponse.UserInfo(
            user.getId(),
            user.getEmail(),
            user.getFirstName(),
            user.getLastName(),
            user.getRole(),
            user.isEmailVerified(),
            user.getTenant().getId(),
            user.getTenant().getSlug()
        );
    }
}

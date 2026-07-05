package fr.pivot.auth.dto;

import fr.pivot.auth.entity.User;

import java.time.Instant;

/**
 * Représentation API-safe d'un utilisateur pour l'écran d'administration des utilisateurs
 * (US06.1.1 — {@code GET /api/admin/users}).
 *
 * <p>Ne jamais substituer par l'entité {@link User} — ce DTO exclut délibérément
 * {@code passwordHash}, {@code googleId}, {@code oidcSubject} et toute autre donnée interne.
 *
 * @param id        identifiant technique de l'utilisateur
 * @param email     adresse e-mail
 * @param firstName prénom (peut être {@code null})
 * @param lastName  nom (peut être {@code null})
 * @param role      rôle Spring Security de l'utilisateur (ex. {@code ROLE_ADMIN})
 * @param status    statut synthétique dérivé (voir {@link UserStatus})
 * @param createdAt horodatage de création du compte
 */
public record AdminUserDto(
        Long id,
        String email,
        String firstName,
        String lastName,
        String role,
        UserStatus status,
        Instant createdAt) {

    /**
     * Convertit une entité {@link User} en {@link AdminUserDto}.
     *
     * @param user entité source
     * @return DTO sûr pour sérialisation API
     */
    public static AdminUserDto from(final User user) {
        return new AdminUserDto(
                user.getId(),
                user.getEmail(),
                user.getFirstName(),
                user.getLastName(),
                user.getRole(),
                UserStatus.from(user),
                user.getCreatedAt());
    }
}

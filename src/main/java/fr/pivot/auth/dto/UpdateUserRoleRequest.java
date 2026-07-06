package fr.pivot.auth.dto;

import jakarta.validation.constraints.NotNull;

/**
 * Payload de {@code PATCH /api/admin/users/{userId}/role} (US06.1.3 « Admin modifie le rôle
 * d'un utilisateur »).
 *
 * <p>Le {@code userId} ciblé provient exclusivement du {@code @PathVariable} — jamais de ce
 * corps de requête. {@code role} est un {@link AssignableRole}, énumération fermée à
 * {@code ROLE_ADMIN}/{@code ROLE_USER} : {@code null} (champ absent) est rejeté par
 * {@code @NotNull}, une valeur hors énumération (ex. {@code "ROLE_SUPER_ADMIN"}) est déjà rejetée
 * en amont par Jackson lors de la désérialisation — les deux cas aboutissent à {@code 400 Bad
 * Request} sans code de validation dédié côté service.
 *
 * @param role le rôle demandé pour l'utilisateur ciblé
 */
public record UpdateUserRoleRequest(@NotNull AssignableRole role) {
}

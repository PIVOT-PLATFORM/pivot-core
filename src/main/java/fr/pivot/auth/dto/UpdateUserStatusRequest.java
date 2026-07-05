package fr.pivot.auth.dto;

import jakarta.validation.constraints.NotNull;

/**
 * Payload de {@code PATCH /api/admin/users/{userId}/status} (US06.1.4 « Admin désactive un
 * compte utilisateur » / US06.1.5 « Admin réactive un compte utilisateur » — les deux US
 * partagent ce même endpoint, un seul champ distinguant les deux directions).
 *
 * <p>Le {@code userId} ciblé provient exclusivement du {@code @PathVariable} — jamais de ce
 * corps de requête. {@code status} est un {@link AssignableStatus}, énumération fermée à
 * {@code ACTIVE}/{@code INACTIVE} : {@code null} (champ absent) est rejeté par {@code @NotNull},
 * une valeur hors énumération (ex. {@code "BLOCKED"}) est déjà rejetée en amont par Jackson lors
 * de la désérialisation — les deux cas aboutissent à {@code 400 Bad Request} sans code de
 * validation dédié côté service.
 *
 * @param status le statut demandé pour l'utilisateur ciblé
 */
public record UpdateUserStatusRequest(@NotNull AssignableStatus status) {
}

package fr.pivot.auth.dto;

import fr.pivot.auth.entity.User;

/**
 * Statut synthétique d'un utilisateur exposé par l'API d'administration (US06.1.1).
 *
 * <p>{@link User} ne porte pas de colonne {@code status} unique — l'état est dérivé de deux
 * booléens indépendants ({@code is_active}, {@code is_blocked}). Ce statut synthétique fournit
 * un contrat API stable côté consommateurs (Angular) sans exposer directement ces deux colonnes.
 *
 * <p><strong>Précédence :</strong> {@link #BLOCKED} l'emporte sur {@link #INACTIVE} — un compte
 * désactivé ET bloqué est rapporté {@code BLOCKED} (l'information la plus sévère/actionnable
 * pour un administrateur).
 */
public enum UserStatus {

    /** Compte actif et non bloqué. */
    ACTIVE,

    /** Compte désactivé ({@code is_active = false}) mais non bloqué. */
    INACTIVE,

    /** Compte bloqué ({@code is_blocked = true}), quel que soit l'état de {@code is_active}. */
    BLOCKED;

    /**
     * Dérive le statut synthétique d'un utilisateur depuis ses colonnes {@code is_active} /
     * {@code is_blocked}.
     *
     * @param user utilisateur source, non {@code null}
     * @return le statut dérivé
     */
    public static UserStatus from(final User user) {
        if (user.isBlocked()) {
            return BLOCKED;
        }
        if (!user.isActive()) {
            return INACTIVE;
        }
        return ACTIVE;
    }
}

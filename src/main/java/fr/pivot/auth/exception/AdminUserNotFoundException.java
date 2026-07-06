package fr.pivot.auth.exception;

/**
 * Levée quand une opération d'administration des utilisateurs (US06.1.3 et suivantes) cible un
 * {@code userId} introuvable dans le tenant de l'administrateur appelant.
 *
 * <p>Recouvre volontairement deux cas indistinguables pour l'appelant : l'utilisateur n'existe
 * pas du tout, ou existe mais appartient à un autre tenant. Traduite en {@code 404 Not Found}
 * (jamais {@code 403}) — voir CLAUDE.md « Règle transversale sécurité — Isolation tenant » : ne
 * jamais confirmer l'existence d'une ressource cross-tenant.
 */
public class AdminUserNotFoundException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /**
     * Construit l'exception pour un utilisateur introuvable dans le tenant courant.
     *
     * @param userId identifiant de l'utilisateur recherché
     */
    public AdminUserNotFoundException(final Long userId) {
        super("User not found: " + userId);
    }
}

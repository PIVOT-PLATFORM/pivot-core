package fr.pivot.modules.registry;

/**
 * Statut opérationnel d'un module PIVOT.
 *
 * <ul>
 *   <li>{@link #ONLINE} — module activé et pleinement opérationnel pour le tenant.</li>
 *   <li>{@link #PREVIEW} — module disponible en avant-première (accès restreint).</li>
 *   <li>{@link #OFFLINE} — module désactivé pour le tenant courant.</li>
 * </ul>
 */
public enum ModuleStatus {

    /** Module activé et pleinement opérationnel pour le tenant. */
    ONLINE,

    /** Module disponible en avant-première (accès restreint). */
    PREVIEW,

    /** Module désactivé pour le tenant courant. */
    OFFLINE
}

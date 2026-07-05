package fr.pivot.auth.exception;

/**
 * Levée quand un filtre de requête de l'API d'administration des utilisateurs (US06.1.1)
 * porte une valeur syntaxiquement invalide — par exemple {@code status=bogus}, qui ne
 * correspond à aucune valeur de {@link fr.pivot.auth.dto.UserStatus}.
 *
 * <p>Traduite en {@code 400 Bad Request} par {@code AdminUserController}. Distincte d'un
 * filtre simplement vide (ignoré) : ici la valeur est présente mais ne peut être interprétée.
 */
public class InvalidUserFilterException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final String field;
    private final String value;

    /**
     * Construit l'exception pour un couple champ/valeur invalide.
     *
     * @param field nom du paramètre de requête concerné (ex. {@code "status"})
     * @param value valeur fournie par l'appelant, non interprétable
     */
    public InvalidUserFilterException(final String field, final String value) {
        super("Invalid value for filter '" + field + "': " + value);
        this.field = field;
        this.value = value;
    }

    /**
     * Nom du paramètre de requête invalide.
     *
     * @return nom du champ (ex. {@code "status"})
     */
    public String getField() {
        return field;
    }

    /**
     * Valeur fournie par l'appelant, non interprétable.
     *
     * @return valeur brute reçue en requête
     */
    public String getValue() {
        return value;
    }
}

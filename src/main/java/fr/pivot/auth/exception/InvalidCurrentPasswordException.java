package fr.pivot.auth.exception;

/**
 * Thrown by {@code POST /account/password} when the supplied current password does not match
 * the stored hash (or, defensively, when the authenticated user id no longer resolves to an
 * account).
 *
 * <p>Translated to 401 with a JSON body shaped identically to {@link
 * ChangePasswordRateLimitException}'s 429 body ({@code {"message": "..."}}) — plain {@link
 * org.springframework.web.server.ResponseStatusException} was deliberately avoided here: without
 * {@code spring.mvc.problemdetails.enabled=true} (not set in this codebase) it renders an empty
 * response body, which would make the anti-enumeration AC ("429 message indistinguishable from
 * 401 message") untestable and, worse, meaningless in production.
 */
public class InvalidCurrentPasswordException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public InvalidCurrentPasswordException(final String message) {
        super(message);
    }
}

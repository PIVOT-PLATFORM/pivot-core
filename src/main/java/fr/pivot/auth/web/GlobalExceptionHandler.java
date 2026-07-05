package fr.pivot.auth.web;

import fr.pivot.auth.exception.ChangePasswordRateLimitException;
import fr.pivot.auth.exception.InvalidCurrentPasswordException;
import fr.pivot.auth.exception.RateLimitException;
import fr.pivot.core.modules.UnknownModuleException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

/**
 * Translates domain exceptions to HTTP responses with structured error bodies.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    /**
     * Returns 429 with {@code Retry-After} header and {@code retryAfterSeconds} in body.
     */
    @ExceptionHandler(RateLimitException.class)
    public ResponseEntity<Map<String, Object>> handleRateLimit(final RateLimitException ex) {
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.RETRY_AFTER, String.valueOf(ex.getRetryAfterSeconds()));
        return ResponseEntity
            .status(HttpStatus.TOO_MANY_REQUESTS)
            .headers(headers)
            .body(Map.of(
                "code", "RATE_LIMITED",
                "retryAfterSeconds", ex.getRetryAfterSeconds()
            ));
    }

    /**
     * Returns 429 with {@code Retry-After} header for the change-password endpoint.
     *
     * <p>Unlike {@link #handleRateLimit}, the body deliberately carries only a generic
     * {@code message} field — never a {@code "code":"RATE_LIMITED"} marker — so it cannot be
     * distinguished from a "current password incorrect" 401 body by content alone
     * (anti-enumeration, see {@link ChangePasswordRateLimitException}).
     */
    @ExceptionHandler(ChangePasswordRateLimitException.class)
    public ResponseEntity<Map<String, Object>> handleChangePasswordRateLimit(final ChangePasswordRateLimitException ex) {
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.RETRY_AFTER, String.valueOf(ex.getRetryAfterSeconds()));
        return ResponseEntity
            .status(HttpStatus.TOO_MANY_REQUESTS)
            .headers(headers)
            .body(Map.of("message", ex.getMessage()));
    }

    /**
     * Returns 401 with body {@code {"message": "..."}} for a wrong current password on the
     * change-password endpoint — same body shape as {@link #handleChangePasswordRateLimit},
     * only the status code and the (absent) {@code Retry-After} header differ.
     */
    @ExceptionHandler(InvalidCurrentPasswordException.class)
    public ResponseEntity<Map<String, Object>> handleInvalidCurrentPassword(final InvalidCurrentPasswordException ex) {
        return ResponseEntity
            .status(HttpStatus.UNAUTHORIZED)
            .body(Map.of("message", ex.getMessage()));
    }

    /**
     * Returns 404 when an operation targets a module id absent from the {@link
     * fr.pivot.core.modules.ModuleRegistry}.
     *
     * <p>404 (not 403 or 400) — the module simply does not exist as a resource; this is
     * distinct from an existing-but-disabled module, which is a 200 with {@code enabled=false}
     * on the status endpoint. No detail about registered module ids is leaked in the body.
     */
    @ExceptionHandler(UnknownModuleException.class)
    public ResponseEntity<Map<String, Object>> handleUnknownModule(final UnknownModuleException ex) {
        return ResponseEntity
            .status(HttpStatus.NOT_FOUND)
            .body(Map.of("code", "MODULE_NOT_FOUND"));
    }
}

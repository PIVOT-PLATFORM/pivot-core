package fr.pivot.auth.web;

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

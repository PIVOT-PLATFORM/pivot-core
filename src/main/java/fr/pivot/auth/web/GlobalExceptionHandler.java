package fr.pivot.auth.web;

import fr.pivot.auth.exception.RateLimitException;
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
}

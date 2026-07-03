package fr.pivot.auth.web;

import fr.pivot.auth.exception.RateLimitException;
import fr.pivot.core.modules.UnknownModuleException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link GlobalExceptionHandler} — HTTP error mapping for domain exceptions.
 */
class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    void handleRateLimit_returns429_withRetryAfterHeaderAndBody() {
        final RateLimitException ex = new RateLimitException(120L);

        final ResponseEntity<Map<String, Object>> resp = handler.handleRateLimit(ex);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(resp.getHeaders().getFirst(HttpHeaders.RETRY_AFTER)).isEqualTo("120");
        assertThat(resp.getBody())
            .containsEntry("code", "RATE_LIMITED")
            .containsEntry("retryAfterSeconds", 120L);
        assertThat(ex.getRetryAfterSeconds()).isEqualTo(120L);
    }

    @Test
    void handleRateLimit_propagatesRetryAfterSeconds() {
        final ResponseEntity<Map<String, Object>> resp = handler.handleRateLimit(new RateLimitException(30L));

        assertThat(resp.getHeaders().getFirst(HttpHeaders.RETRY_AFTER)).isEqualTo("30");
        assertThat(resp.getBody()).containsEntry("retryAfterSeconds", 30L);
    }

    @Test
    void handleUnknownModule_returns404_withModuleNotFoundCode() {
        final UnknownModuleException ex = new UnknownModuleException("ghost");

        final ResponseEntity<Map<String, Object>> resp = handler.handleUnknownModule(ex);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(resp.getBody()).containsEntry("code", "MODULE_NOT_FOUND");
        // Security: body must not leak the requested module id or the list of registered ids.
        assertThat(resp.getBody()).doesNotContainValue("ghost");
    }
}

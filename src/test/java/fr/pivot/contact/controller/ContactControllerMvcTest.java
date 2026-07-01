package fr.pivot.contact.controller;

import fr.pivot.auth.exception.RateLimitException;
import fr.pivot.auth.service.RateLimiterService;
import fr.pivot.auth.web.GlobalExceptionHandler;
import fr.pivot.contact.service.ContactService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Duration;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Standalone MockMvc tests for {@link ContactController}.
 *
 * <p>Tests the full HTTP dispatch layer without a Spring context: input validation (400),
 * rate-limit exception mapping to 429 with {@code Retry-After} header via
 * {@link GlobalExceptionHandler}, and 202 Accepted on success.
 * No Redis or mail side effects — services are mocked.
 */
@ExtendWith(MockitoExtension.class)
class ContactControllerMvcTest {

    @Mock
    private ContactService contactService;

    @Mock
    private RateLimiterService rateLimiter;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        final ContactController controller = new ContactController(contactService, rateLimiter);
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
        lenient().when(rateLimiter.contactIpBucket(any())).thenReturn("contact:ip:127.0.0.1");
    }

    @Test
    void submit_returns_202_on_valid_payload() throws Exception {
        when(rateLimiter.checkAndRecord(any(), anyInt(), any(Duration.class))).thenReturn(true);

        mockMvc.perform(post("/contact")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"alice@example.com\",\"message\":\"Hello team\",\"lang\":\"fr\"}"))
                .andExpect(status().isAccepted());

        verify(contactService).processContact(any());
    }

    @Test
    void submit_returns_202_when_lang_is_null() throws Exception {
        when(rateLimiter.checkAndRecord(any(), anyInt(), any(Duration.class))).thenReturn(true);

        mockMvc.perform(post("/contact")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"bob@example.com\",\"message\":\"Hi\",\"lang\":null}"))
                .andExpect(status().isAccepted());

        verify(contactService).processContact(any());
    }

    @Test
    void submit_returns_400_on_invalid_email() throws Exception {
        mockMvc.perform(post("/contact")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"not-an-email\",\"message\":\"Hello\",\"lang\":\"fr\"}"))
                .andExpect(status().isBadRequest());

        verify(contactService, never()).processContact(any());
        verify(rateLimiter, never()).checkAndRecord(any(), anyInt(), any(Duration.class));
    }

    @Test
    void submit_returns_400_on_blank_message() throws Exception {
        mockMvc.perform(post("/contact")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"alice@example.com\",\"message\":\"   \",\"lang\":\"fr\"}"))
                .andExpect(status().isBadRequest());

        verify(contactService, never()).processContact(any());
    }

    @Test
    void submit_returns_429_with_retry_after_header_when_rate_limited() throws Exception {
        when(rateLimiter.checkAndRecord(any(), anyInt(), any(Duration.class))).thenReturn(false);
        when(rateLimiter.getRemainingSeconds(any())).thenReturn(300L);

        mockMvc.perform(post("/contact")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"flood@example.com\",\"message\":\"Flood\",\"lang\":\"fr\"}"))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string("Retry-After", "300"))
                .andExpect(jsonPath("$.code").value("RATE_LIMITED"))
                .andExpect(jsonPath("$.retryAfterSeconds").value(300));

        verify(contactService, never()).processContact(any());
    }
}

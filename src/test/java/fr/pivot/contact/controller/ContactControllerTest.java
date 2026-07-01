package fr.pivot.contact.controller;

import fr.pivot.auth.exception.RateLimitException;
import fr.pivot.auth.service.RateLimiterService;
import fr.pivot.contact.dto.ContactRequestDto;
import fr.pivot.contact.service.ContactService;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link ContactController}.
 */
@ExtendWith(MockitoExtension.class)
class ContactControllerTest {

    private static final String TEST_IP = "192.168.1.1";
    private static final String BUCKET = "contact:ip:" + TEST_IP;

    @Mock
    private ContactService contactService;

    @Mock
    private RateLimiterService rateLimiter;

    @Mock
    private HttpServletRequest request;

    @InjectMocks
    private ContactController controller;

    @BeforeEach
    void setUp() {
        when(request.getRemoteAddr()).thenReturn(TEST_IP);
        when(rateLimiter.contactIpBucket(TEST_IP)).thenReturn(BUCKET);
    }

    @Test
    void submit_delegates_to_service_when_rate_limit_ok() {
        when(rateLimiter.checkAndRecord(eq(BUCKET), anyInt(), any(Duration.class))).thenReturn(true);

        final var dto = new ContactRequestDto("alice@example.com", "Hello team", "fr");
        controller.submit(dto, request);

        verify(contactService).processContact(dto);
    }

    @Test
    void submit_accepts_null_lang_when_rate_limit_ok() {
        when(rateLimiter.checkAndRecord(eq(BUCKET), anyInt(), any(Duration.class))).thenReturn(true);

        final var dto = new ContactRequestDto("alice@example.com", "Hi", null);
        controller.submit(dto, request);

        verify(contactService).processContact(any());
    }

    @Test
    void submit_throws_rate_limit_exception_when_limit_exceeded() {
        when(rateLimiter.checkAndRecord(eq(BUCKET), anyInt(), any(Duration.class))).thenReturn(false);
        when(rateLimiter.getRemainingSeconds(BUCKET)).thenReturn(300L);

        final var dto = new ContactRequestDto("alice@example.com", "Flood attempt", "fr");

        assertThatThrownBy(() -> controller.submit(dto, request))
                .isInstanceOf(RateLimitException.class)
                .extracting("retryAfterSeconds")
                .isEqualTo(300L);

        verify(contactService, never()).processContact(any());
    }

    @Test
    void submit_uses_client_ip_for_bucket() {
        when(rateLimiter.checkAndRecord(eq(BUCKET), anyInt(), any(Duration.class))).thenReturn(true);

        controller.submit(new ContactRequestDto("a@b.com", "msg", "fr"), request);

        verify(rateLimiter).contactIpBucket(TEST_IP);
        verify(rateLimiter).checkAndRecord(eq(BUCKET), eq(5), eq(Duration.ofMinutes(10)));
    }
}

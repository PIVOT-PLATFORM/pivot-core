package fr.pivot.auth.service;

import fr.pivot.auth.dto.GoogleAuthRequest;
import fr.pivot.auth.repository.UserRepository;
import fr.pivot.tenant.repository.TenantRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * Unit test for {@link GoogleAuthService} — covers the rate-limit guard.
 * Google ID-token verification (static {@code GoogleIdTokenVerifier}) is exercised
 * by integration tests, not unit tests.
 */
@ExtendWith(MockitoExtension.class)
class GoogleAuthServiceTest {

    @Mock private UserRepository userRepo;
    @Mock private TenantRepository tenantRepo;
    @Mock private TokenService tokenService;
    @Mock private AuditService auditService;
    @Mock private TrustedDeviceService trustedDeviceService;
    @Mock private RateLimiterService rateLimiter;

    private GoogleAuthService service;

    @BeforeEach
    void setUp() {
        service = new GoogleAuthService(userRepo, tenantRepo, tokenService, auditService,
            trustedDeviceService, rateLimiter, "client-id");
    }

    @Test
    void authenticate_throws429_whenRateLimited() {
        when(rateLimiter.checkAndRecord(anyString(), anyInt(), any())).thenReturn(false);

        final GoogleAuthRequest req = new GoogleAuthRequest("id-token", "fp", "Chrome");
        assertThatThrownBy(() -> service.authenticate(req, "ip", "ua"))
            .isInstanceOf(ResponseStatusException.class)
            .extracting(e -> ((ResponseStatusException) e).getStatusCode())
            .isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
    }
}

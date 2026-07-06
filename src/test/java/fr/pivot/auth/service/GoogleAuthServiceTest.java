package fr.pivot.auth.service;

import com.google.api.client.googleapis.auth.oauth2.GoogleIdToken;
import fr.pivot.auth.dto.GoogleAuthRequest;
import fr.pivot.auth.repository.UserRepository;
import fr.pivot.tenant.entity.Tenant;
import fr.pivot.tenant.repository.TenantRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

/**
 * Unit test for {@link GoogleAuthService} — covers the rate-limit guard plus the
 * post-verification account-resolution branches. {@code verifyGoogleToken} is spied and
 * stubbed with a real {@link GoogleIdToken.Payload}, so {@code authenticate()}'s own logic
 * (including branches reachable only after a successful Google verify) runs for real.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class GoogleAuthServiceTest {

    @Mock private UserRepository userRepo;
    @Mock private TenantRepository tenantRepo;
    @Mock private TokenService tokenService;
    @Mock private AuditService auditService;
    @Mock private TrustedDeviceService trustedDeviceService;
    @Mock private RateLimiterService rateLimiter;
    @Mock private Tenant tenant;

    private GoogleAuthService service;

    @BeforeEach
    void setUp() {
        service = new GoogleAuthService(userRepo, tenantRepo, tokenService, auditService,
            trustedDeviceService, rateLimiter, "client-id");
        when(rateLimiter.checkAndRecord(anyString(), anyInt(), any())).thenReturn(true);
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

    @Test
    void authenticate_throws403_whenAccountSoftDeleted() {
        final GoogleIdToken.Payload payload = new GoogleIdToken.Payload()
            .setSubject("google-sub-1")
            .setEmail("deleted@acme.test");
        final GoogleAuthService spied = spy(service);
        doReturn(payload).when(spied).verifyGoogleToken(anyString());

        when(tenant.getId()).thenReturn(1L);
        when(tenantRepo.findBySlug("pivot-saas")).thenReturn(Optional.of(tenant));
        when(userRepo.findByGoogleIdAndDeletedAtIsNull("google-sub-1")).thenReturn(Optional.empty());
        when(userRepo.findByTenantIdAndEmailAndDeletedAtIsNull(1L, "deleted@acme.test"))
            .thenReturn(Optional.empty());
        when(userRepo.existsByTenantIdAndEmail(1L, "deleted@acme.test")).thenReturn(true);

        final GoogleAuthRequest req = new GoogleAuthRequest("id-token", "fp", "Chrome");
        assertThatThrownBy(() -> spied.authenticate(req, "ip", "ua"))
            .isInstanceOf(ResponseStatusException.class)
            .extracting(e -> ((ResponseStatusException) e).getStatusCode())
            .isEqualTo(HttpStatus.FORBIDDEN);
    }
}

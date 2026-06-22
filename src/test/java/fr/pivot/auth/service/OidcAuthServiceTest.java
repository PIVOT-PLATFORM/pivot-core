package fr.pivot.auth.service;

import fr.pivot.auth.dto.OidcExchangeRequest;
import fr.pivot.auth.repository.UserRepository;
import fr.pivot.tenant.entity.Tenant;
import fr.pivot.tenant.entity.TenantOidcConfig;
import fr.pivot.tenant.repository.TenantOidcConfigRepository;
import fr.pivot.tenant.repository.TenantRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jwt.JwtValidationException;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.web.server.ResponseStatusException;

import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link OidcAuthService} — covers the rate-limit, tenant and OIDC-config
 * lookups plus {@code getClientConfig}. JWT verification (static {@code JwtDecoders})
 * is exercised by integration tests, not unit tests.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class OidcAuthServiceTest {

    @Mock private TenantRepository tenantRepo;
    @Mock private TenantOidcConfigRepository oidcConfigRepo;
    @Mock private UserRepository userRepo;
    @Mock private TokenService tokenService;
    @Mock private AuditService auditService;
    @Mock private TrustedDeviceService trustedDeviceService;
    @Mock private RateLimiterService rateLimiter;
    @Mock private Tenant tenant;
    @Mock private TenantOidcConfig oidcConfig;

    private OidcAuthService service;

    @BeforeEach
    void setUp() {
        service = new OidcAuthService(tenantRepo, oidcConfigRepo, userRepo, tokenService,
            auditService, trustedDeviceService, rateLimiter,
            tools.jackson.databind.json.JsonMapper.builder().build());
        when(rateLimiter.checkAndRecord(anyString(), anyInt(), any())).thenReturn(true);
        when(tenant.getId()).thenReturn(1L);
    }

    private OidcExchangeRequest req() {
        return new OidcExchangeRequest("acme", "access-token", "fp", "Chrome");
    }

    @Test
    void exchange_throws429_whenRateLimited() {
        when(rateLimiter.checkAndRecord(anyString(), anyInt(), any())).thenReturn(false);

        final OidcExchangeRequest r = req();
        assertThatThrownBy(() -> service.exchange(r, "ip", "ua"))
            .isInstanceOf(ResponseStatusException.class)
            .extracting(e -> ((ResponseStatusException) e).getStatusCode())
            .isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
    }

    @Test
    void exchange_throws404_whenTenantUnknown() {
        when(tenantRepo.findBySlug("acme")).thenReturn(Optional.empty());

        final OidcExchangeRequest r = req();
        assertThatThrownBy(() -> service.exchange(r, "ip", "ua"))
            .isInstanceOf(ResponseStatusException.class)
            .extracting(e -> ((ResponseStatusException) e).getStatusCode())
            .isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void exchange_throws400_whenOidcNotConfigured() {
        when(tenantRepo.findBySlug("acme")).thenReturn(Optional.of(tenant));
        when(oidcConfigRepo.findByTenantId(1L)).thenReturn(Optional.empty());

        final OidcExchangeRequest r = req();
        assertThatThrownBy(() -> service.exchange(r, "ip", "ua"))
            .isInstanceOf(ResponseStatusException.class)
            .extracting(e -> ((ResponseStatusException) e).getStatusCode())
            .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void exchange_throws403_whenOidcConfigInactive() {
        when(tenantRepo.findBySlug("acme")).thenReturn(Optional.of(tenant));
        when(oidcConfigRepo.findByTenantId(1L)).thenReturn(Optional.of(oidcConfig));
        when(oidcConfig.isActive()).thenReturn(false);

        final OidcExchangeRequest r = req();
        assertThatThrownBy(() -> service.exchange(r, "ip", "ua"))
            .isInstanceOf(ResponseStatusException.class)
            .extracting(e -> ((ResponseStatusException) e).getStatusCode())
            .isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void getClientConfig_returnsConfig_whenFound() {
        when(tenantRepo.findBySlug("acme")).thenReturn(Optional.of(tenant));
        when(oidcConfigRepo.findByTenantId(1L)).thenReturn(Optional.of(oidcConfig));
        when(oidcConfig.getIssuerUri()).thenReturn("https://idp");
        when(oidcConfig.getClientId()).thenReturn("client-1");
        when(oidcConfig.getScopes()).thenReturn("openid email");

        final OidcAuthService.OidcClientConfig cfg = service.getClientConfig("acme");

        assertThat(cfg.issuerUri()).isEqualTo("https://idp");
        assertThat(cfg.clientId()).isEqualTo("client-1");
        assertThat(cfg.scopes()).isEqualTo("openid email");
    }

    @Test
    void getClientConfig_throws404_whenTenantUnknown() {
        when(tenantRepo.findBySlug("acme")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getClientConfig("acme"))
            .isInstanceOf(ResponseStatusException.class)
            .extracting(e -> ((ResponseStatusException) e).getStatusCode())
            .isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void getClientConfig_throws404_whenOidcConfigMissing() {
        when(tenantRepo.findBySlug("acme")).thenReturn(Optional.of(tenant));
        when(oidcConfigRepo.findByTenantId(1L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getClientConfig("acme"))
            .isInstanceOf(ResponseStatusException.class)
            .extracting(e -> ((ResponseStatusException) e).getStatusCode())
            .isEqualTo(HttpStatus.NOT_FOUND);
    }

    // ----------------------------------------------------------------
    // Audience validation (Red Team merge condition) — exercises the exact
    // validator chain built by OidcAuthService.oidcValidator against a locally
    // signed token, with no network discovery.
    // ----------------------------------------------------------------

    private static final String ISSUER = "https://idp.example.com";
    private static final String CLIENT_ID = "pivot-client";

    @Test
    void oidcValidator_rejectsToken_withWrongAudience() throws Exception {
        final var kp = rsaKeyPair();
        final NimbusJwtDecoder decoder = NimbusJwtDecoder.withPublicKey((RSAPublicKey) kp.getPublic()).build();
        when(oidcConfig.getIssuerUri()).thenReturn(ISSUER);
        when(oidcConfig.getClientId()).thenReturn(CLIENT_ID);
        when(oidcConfig.getAzureTenantId()).thenReturn(null);
        decoder.setJwtValidator(service.oidcValidator(oidcConfig));

        final String token = signToken((RSAPrivateKey) kp.getPrivate(), List.of("some-other-client"));

        assertThatThrownBy(() -> decoder.decode(token))
            .isInstanceOf(JwtValidationException.class)
            .hasMessageContaining("aud");
    }

    @Test
    void oidcValidator_acceptsToken_withCorrectAudience() throws Exception {
        final var kp = rsaKeyPair();
        final NimbusJwtDecoder decoder = NimbusJwtDecoder.withPublicKey((RSAPublicKey) kp.getPublic()).build();
        when(oidcConfig.getIssuerUri()).thenReturn(ISSUER);
        when(oidcConfig.getClientId()).thenReturn(CLIENT_ID);
        when(oidcConfig.getAzureTenantId()).thenReturn(null);
        decoder.setJwtValidator(service.oidcValidator(oidcConfig));

        final String token = signToken((RSAPrivateKey) kp.getPrivate(), List.of(CLIENT_ID));

        assertThatCode(() -> {
            final var jwt = decoder.decode(token);
            assertThat(jwt.getSubject()).isEqualTo("user-1");
        }).doesNotThrowAnyException();
    }

    private static java.security.KeyPair rsaKeyPair() throws Exception {
        final KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA");
        kpg.initialize(2048);
        return kpg.generateKeyPair();
    }

    private static String signToken(final RSAPrivateKey priv, final List<String> audience) throws Exception {
        final JWTClaimsSet claims = new JWTClaimsSet.Builder()
            .issuer(ISSUER)
            .subject("user-1")
            .audience(audience)
            .issueTime(new Date())
            .expirationTime(Date.from(Instant.now().plusSeconds(300)))
            .build();
        final SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.RS256), claims);
        jwt.sign(new RSASSASigner(priv));
        return jwt.serialize();
    }
}

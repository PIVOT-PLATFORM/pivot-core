package fr.pivot.auth.service;

import fr.pivot.auth.dto.AuthResponse;
import fr.pivot.auth.dto.OidcExchangeRequest;
import fr.pivot.auth.entity.AuthMethod;
import fr.pivot.auth.entity.User;
import fr.pivot.auth.mapper.UserMapper;
import fr.pivot.auth.repository.UserRepository;
import fr.pivot.tenant.entity.Tenant;
import fr.pivot.tenant.entity.TenantOidcConfig;
import fr.pivot.tenant.repository.TenantOidcConfigRepository;
import fr.pivot.tenant.repository.TenantRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtDecoders;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Handles enterprise OIDC (OpenID Connect) authentication via the PKCE flow.
 *
 * <p>Angular performs the PKCE exchange against the enterprise IdP, then sends
 * the resulting access token here for verification against the tenant's JWKS endpoint.
 * On success, issues an opaque session token via {@link TokenService}.
 *
 * <p>Uses {@code spring-security-oauth2-jose} directly (no Spring Boot auto-config starter),
 * avoiding conflicts with the main opaque-token {@link fr.pivot.config.SecurityConfig}.
 * Each tenant has its own OIDC configuration ({@code TenantOidcConfig}) containing
 * the issuer URI, client ID and scopes.
 */
@Service
public class OidcAuthService {

    private static final Logger LOG = LoggerFactory.getLogger(OidcAuthService.class);

    private final TenantRepository tenantRepo;
    private final TenantOidcConfigRepository oidcConfigRepo;
    private final UserRepository userRepo;
    private final TokenService tokenService;
    private final AuditService auditService;
    private final TrustedDeviceService trustedDeviceService;
    private final RateLimiterService rateLimiter;
    private final ObjectMapper objectMapper;

    private static final TypeReference<Map<String, String>> MAP_TYPE = new TypeReference<>() {};
    private static final ClaimNames DEFAULT_CLAIMS = new ClaimNames("email", "given_name", "family_name");

    /**
     * Per-IdP {@link JwtDecoder} cache. OIDC discovery ({@code .well-known} + JWKS fetch)
     * is a network round-trip — caching avoids performing it on every {@link #exchange}
     * call (notably inside the surrounding {@code @Transactional}). Keyed by
     * issuer + client + Azure tenant so distinct tenants never share a validator chain.
     */
    private final Map<String, JwtDecoder> decoderCache = new ConcurrentHashMap<>();

    /**
     * Constructs the service with its required collaborators.
     *
     * @param tenantRepo          JPA repository for tenants
     * @param oidcConfigRepo      JPA repository for per-tenant OIDC configurations
     * @param userRepo            JPA repository for users
     * @param tokenService        opaque session token lifecycle manager
     * @param auditService        async audit event logger
     * @param trustedDeviceService manages trusted device records
     * @param rateLimiter         sliding-window rate limiter backed by Redis
     * @param objectMapper        JSON mapper used to parse the per-tenant claims mapping
     */
    public OidcAuthService(
            final TenantRepository tenantRepo,
            final TenantOidcConfigRepository oidcConfigRepo,
            final UserRepository userRepo,
            final TokenService tokenService,
            final AuditService auditService,
            final TrustedDeviceService trustedDeviceService,
            final RateLimiterService rateLimiter,
            final ObjectMapper objectMapper) {
        this.tenantRepo = tenantRepo;
        this.oidcConfigRepo = oidcConfigRepo;
        this.userRepo = userRepo;
        this.tokenService = tokenService;
        this.auditService = auditService;
        this.trustedDeviceService = trustedDeviceService;
        this.rateLimiter = rateLimiter;
        this.objectMapper = objectMapper;
    }

    /**
     * Exchanges an enterprise IdP access token for a PIVOT opaque session token.
     *
     * <p>Validates the access token against the tenant's issuer via OIDC discovery.
     * Creates a new user account if {@code auto_provision_users} is enabled
     * and no existing account matches the OIDC subject.
     *
     * @param req       OIDC exchange payload (tenant slug, access token, optional device info)
     * @param ip        client IP for rate limiting and audit
     * @param userAgent browser user-agent for audit and device metadata
     * @return {@link OidcLoginResult} containing the session token and user info
     * @throws ResponseStatusException 401 on invalid OIDC token, 403 on blocked account,
     *     404 on unknown tenant or unconfigured OIDC, 429 on rate limit
     */
    @Transactional
    public OidcLoginResult exchange(final OidcExchangeRequest req, final String ip, final String userAgent) {
        if (!rateLimiter.checkAndRecord("oidc:ip:" + ip, 20, Duration.ofMinutes(15))) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS);
        }

        final Tenant tenant = tenantRepo.findBySlug(req.tenantSlug())
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Tenant introuvable"));

        final TenantOidcConfig config = oidcConfigRepo.findByTenantId(tenant.getId())
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "OIDC non configuré pour ce tenant"));

        if (!config.isActive()) {
            LOG.warn("event=OIDC_CONFIG_INACTIVE tenant={}", req.tenantSlug());
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Fournisseur OIDC désactivé pour ce tenant");
        }

        final Jwt jwt;
        try {
            jwt = decoderFor(config).decode(req.accessToken());
        } catch (final Exception e) {
            LOG.warn("event=OIDC_TOKEN_INVALID tenant={} reason={}", req.tenantSlug(), e.getMessage());
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Token OIDC invalide");
        }

        final ClaimNames claims = claimNames(config);
        final String subject = jwt.getSubject();
        final String email = jwt.getClaimAsString(claims.email());
        final String firstName = jwt.getClaimAsString(claims.firstName());
        final String lastName = jwt.getClaimAsString(claims.lastName());
        final String avatarUrl = jwt.getClaimAsString("picture");

        if (email == null || email.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Email absent du token OIDC");
        }

        final User user = userRepo.findByTenantIdAndOidcSubjectAndDeletedAtIsNull(tenant.getId(), subject)
            .orElseGet(() -> {
                if (!config.isAutoProvisionUsers()) {
                    throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                        "Provisionnement automatique désactivé pour ce tenant");
                }
                return createOidcUser(tenant, subject, email.toLowerCase(), firstName, lastName,
                    config.getDefaultRole(), avatarUrl);
            });

        if (!user.isActive() || user.isBlocked()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Compte désactivé");
        }

        if (req.deviceFingerprint() != null && !req.deviceFingerprint().isBlank()) {
            trustedDeviceService.trust(user, req.deviceFingerprint(), req.deviceName());
        }

        userRepo.updateLastLoginAt(user.getId());

        final TokenService.TokenIssueResult issued = tokenService.issue(
            user, req.deviceFingerprint(), req.deviceName(), userAgent, ip, AuthMethod.OIDC, false);

        auditService.log(user, AuditService.OIDC_LOGIN, ip, userAgent);
        LOG.info("event=OIDC_LOGIN_SUCCESS userId={} tenant={}", user.getId(), req.tenantSlug());
        return new OidcLoginResult(
            issued.rawToken(),
            issued.expiresAt().toEpochMilli(),
            issued.ttlSeconds(),
            UserMapper.toUserInfo(user));
    }

    /**
     * Returns the OIDC client configuration for a tenant.
     *
     * <p>Angular fetches this before starting the PKCE flow to discover
     * the IdP issuer URI, client ID and requested scopes.
     *
     * @param tenantSlug the tenant's URL slug
     * @return OIDC client config for the given tenant
     * @throws ResponseStatusException 404 if tenant or OIDC config not found
     */
    public OidcClientConfig getClientConfig(final String tenantSlug) {
        final Tenant tenant = tenantRepo.findBySlug(tenantSlug)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        final TenantOidcConfig config = oidcConfigRepo.findByTenantId(tenant.getId())
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "OIDC non configuré"));
        return new OidcClientConfig(config.getIssuerUri(), config.getClientId(), config.getScopes());
    }

    // ----------------------------------------------------------------
    // Private helpers
    // ----------------------------------------------------------------

    /**
     * Returns a cached {@link JwtDecoder} for the tenant's IdP, building it on first use.
     *
     * <p>The validator chain enforces, beyond the default signature + issuer + expiry checks:
     * <ul>
     *   <li><b>audience</b> — the {@code aud} claim must contain the tenant's {@code client_id},
     *       rejecting tokens minted by the same IdP for a different client (cross-client bypass);</li>
     *   <li><b>Azure {@code tid}</b> — when {@code azure_tenant_id} is configured, the token's
     *       {@code tid} claim must match it, blocking tokens from other Azure AD tenants.</li>
     * </ul>
     *
     * @param config the tenant's OIDC configuration
     * @return a validating decoder, cached per issuer + client + Azure tenant
     */
    private JwtDecoder decoderFor(final TenantOidcConfig config) {
        final String cacheKey = config.getIssuerUri() + '|' + config.getClientId()
            + '|' + config.getAzureTenantId();
        return decoderCache.computeIfAbsent(cacheKey, key -> {
            final NimbusJwtDecoder decoder =
                (NimbusJwtDecoder) JwtDecoders.fromIssuerLocation(config.getIssuerUri());
            decoder.setJwtValidator(oidcValidator(config));
            return decoder;
        });
    }

    /**
     * Builds the validator chain applied to every OIDC token: default signature/issuer/expiry,
     * plus audience ({@code aud} contains {@code client_id}) and, for Azure, {@code tid} match.
     *
     * <p>Package-private so the security property (notably audience rejection) can be exercised
     * by unit tests against a locally-signed token without network discovery.
     *
     * @param config the tenant's OIDC configuration
     * @return the composed token validator
     */
    OAuth2TokenValidator<Jwt> oidcValidator(final TenantOidcConfig config) {
        final List<OAuth2TokenValidator<Jwt>> validators = new ArrayList<>();
        validators.add(JwtValidators.createDefaultWithIssuer(config.getIssuerUri()));
        validators.add(new JwtClaimValidator<List<String>>(
            "aud", aud -> aud != null && aud.contains(config.getClientId())));
        if (config.getAzureTenantId() != null && !config.getAzureTenantId().isBlank()) {
            validators.add(new JwtClaimValidator<String>(
                "tid", tid -> config.getAzureTenantId().equals(tid)));
        }
        return new DelegatingOAuth2TokenValidator<>(validators);
    }

    /**
     * Resolves which IdP claims carry email / first name / last name for this tenant,
     * from the {@code claims_mapping} JSON. Falls back to the OIDC standard claims when the
     * mapping is absent, malformed or missing a key.
     *
     * @param config the tenant's OIDC configuration
     * @return the resolved claim names
     */
    private ClaimNames claimNames(final TenantOidcConfig config) {
        final String json = config.getClaimsMapping();
        if (json == null || json.isBlank()) {
            return DEFAULT_CLAIMS;
        }
        try {
            final Map<String, String> m = objectMapper.readValue(json, MAP_TYPE);
            return new ClaimNames(
                m.getOrDefault("email", DEFAULT_CLAIMS.email()),
                m.getOrDefault("first_name", DEFAULT_CLAIMS.firstName()),
                m.getOrDefault("last_name", DEFAULT_CLAIMS.lastName()));
        } catch (final Exception e) {
            LOG.warn("event=OIDC_CLAIMS_MAPPING_INVALID tenant={} reason={}",
                config.getTenant() != null ? config.getTenant().getId() : "?", e.getMessage());
            return DEFAULT_CLAIMS;
        }
    }

    private User createOidcUser(final Tenant tenant, final String subject, final String email,
                                 final String firstName, final String lastName,
                                 final String role, final String avatarUrl) {
        final User u = new User();
        u.setTenant(tenant);
        u.setOidcSubject(subject);
        u.setEmail(email);
        u.setFirstName(firstName);
        u.setLastName(lastName);
        u.setEmailVerified(true);
        if (role != null && !role.isBlank()) {
            u.setRole(role);
        }
        u.setAvatarUrl(avatarUrl);
        return userRepo.save(u);
    }

    /**
     * IdP claim names carrying the user's email, first name and last name.
     *
     * @param email     claim name for the email
     * @param firstName claim name for the first name
     * @param lastName  claim name for the last name
     */
    private record ClaimNames(String email, String firstName, String lastName) {}

    // ----------------------------------------------------------------
    // Result records
    // ----------------------------------------------------------------

    /**
     * Carries the session token and user metadata returned after OIDC authentication.
     *
     * @param sessionToken raw opaque token — send to client as Bearer + set in session cookie
     * @param expiresAt    epoch-millisecond timestamp of token expiry
     * @param ttlSeconds   TTL in seconds (for cookie {@code Max-Age})
     * @param user         authenticated user information
     */
    public record OidcLoginResult(
        String sessionToken,
        long expiresAt,
        int ttlSeconds,
        AuthResponse.UserInfo user) {}

    /**
     * OIDC provider configuration returned to Angular before the PKCE flow.
     *
     * @param issuerUri the IdP issuer URI (used for JWKS discovery)
     * @param clientId  the OAuth2 client ID registered with the IdP
     * @param scopes    space-separated list of OAuth2 scopes to request
     */
    public record OidcClientConfig(String issuerUri, String clientId, String scopes) {}
}

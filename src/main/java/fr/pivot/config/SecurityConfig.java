package fr.pivot.config;

import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

/**
 * Spring Security configuration — opaque token authentication (US-AUTH-002).
 *
 * <p>JWT HS512 and the {@code pivot.jwt.secret} have been removed. Authentication
 * is now handled by {@link TokenAuthenticationFilter}, which validates DB-backed
 * opaque tokens on every request. OIDC enterprise IdP validation remains available
 * via {@link fr.pivot.auth.service.OidcAuthService}.
 *
 * <p>Session creation policy remains {@code STATELESS} — no server-side HTTP session.
 * Token state is stored in the {@code access_tokens} table.
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

    @Value("${pivot.cors.allowed-origins}")
    private String allowedOrigins;

    private final TokenAuthenticationFilter tokenAuthFilter;
    private final RequestMdcFilter requestMdcFilter;

    /**
     * Constructs the security configuration.
     *
     * @param tokenAuthFilter  the opaque token validation filter
     * @param requestMdcFilter EN04.1 — populates SLF4J MDC ({@code requestId}/{@code tenantId}/
     *                         {@code userId}) once the token filter has resolved the request's
     *                         authentication, for every downstream structured log line
     */
    public SecurityConfig(final TokenAuthenticationFilter tokenAuthFilter,
                           final RequestMdcFilter requestMdcFilter) {
        this.tokenAuthFilter = tokenAuthFilter;
        this.requestMdcFilter = requestMdcFilter;
    }

    /**
     * Configures the security filter chain.
     *
     * <p>Public routes: CORS preflight, all {@code /auth/**} endpoints, and
     * {@code GET /account/email/confirm} — the email-change confirmation link (US02.2.2)
     * is opened from an emailed link and must work even without an active PIVOT session on
     * that device; identity there comes solely from the single-use token, never from a bearer
     * token. All other routes require a valid opaque token validated by
     * {@link TokenAuthenticationFilter}.
     *
     * <p>{@code /actuator/**} is also permitted here (EN04.2) — not because it is reachable
     * through this port's own routes (it moved to a separate management port,
     * {@code management.server.port}, see {@code application.yml}, no longer mapped at all
     * on this main context), but because Spring Boot's own
     * {@code ServletManagementChildContextConfiguration} <strong>reuses this exact assembled
     * filter</strong> as the management child context's own security filter whenever it
     * detects one configured in the parent context ({@code @ConditionalOnBean(name =
     * "springSecurityFilterChain", search = ANCESTORS)}) — a deliberate Spring Boot default so
     * a separately-ported management endpoint never becomes an accidental bypass of the main
     * application's security. A dedicated {@code SecurityFilterChain} bean scoped to the
     * management child context (tried first) is therefore not the right lever here: it never
     * becomes the filter Tomcat actually invokes for that context, only this one does. Access
     * control for the management port is meant to be enforced at the network layer (Docker
     * internal network only, no published host port — EN07.1), not by application-level
     * authentication — hence permitAll rather than a narrower rule, on both ports at once.
     *
     * @param http Spring Security HTTP configuration
     * @return configured {@link SecurityFilterChain}
     */
    @Bean
    public SecurityFilterChain filterChain(final HttpSecurity http) {
        try {
            http
                .cors(cors -> cors.configurationSource(corsConfigurationSource()))
                // CSRF désactivé : API stateless à tokens opaques (Bearer), sans cookie de
                // session ambiant exploitable en CSRF. Suppression Semgrep gérée côté config
                // (--exclude-rule dans security.yml), pas en commentaire nosemgrep.
                .csrf(csrf -> csrf.disable())
                .httpBasic(basic -> basic.disable())
                .formLogin(form -> form.disable())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                    .requestMatchers("/error").permitAll()
                    // EN04.2 — see Javadoc above: reused verbatim as the management child
                    // context's own security filter, that is where this rule actually matters.
                    // Listed explicitly (not a "/actuator/**" wildcard) to mirror
                    // management.endpoints.web.exposure.include (application.yml) exactly —
                    // a future endpoint added to that include list (env, shutdown, heapdump…)
                    // must fail closed here until this list is deliberately updated too, not
                    // silently inherit permitAll from a blanket pattern.
                    .requestMatchers("/actuator/health", "/actuator/info", "/actuator/metrics",
                        "/actuator/metrics/**",
                        // EN04.4 — readiness/liveness health groups (application.yml,
                        // management.endpoint.health.group.*) are served as sub-paths of the
                        // "health" endpoint, e.g. /actuator/health/readiness — NOT matched by
                        // the exact "/actuator/health" pattern above (this is exact request
                        // matching, not a path prefix), so listed explicitly here too, same
                        // least-exposure discipline as the rest of this list.
                        "/actuator/health/readiness", "/actuator/health/liveness").permitAll()
                    .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                    .requestMatchers("/auth/**").permitAll()
                    .requestMatchers(HttpMethod.GET, "/account/email/confirm").permitAll()
                    // US02.2.4 — the account-deletion cancellation link is opened from an emailed
                    // link, and every session was revoked the moment the deletion was requested,
                    // so there is by definition no active PIVOT session on any device at that
                    // point. Identity comes solely from the single-use cancellation token.
                    .requestMatchers(HttpMethod.POST, "/account/deletion/cancel").permitAll()
                    .requestMatchers(HttpMethod.POST, "/contact").permitAll()
                    // US02.1.1 — avatar images served as a public static resource (unguessable
                    // UUID filename, see AvatarWebConfig) so a plain <img src> works with no
                    // per-request auth plumbing. GET only.
                    .requestMatchers(HttpMethod.GET, "/avatars/**").permitAll()
                    // EN-NOTIF — the WebSocket handshake itself cannot carry a custom
                    // Authorization header (browsers' native WebSocket API forbids arbitrary
                    // headers on the upgrade request), so this route is public at the HTTP
                    // layer. Real authentication happens on the first STOMP CONNECT frame
                    // (which does carry arbitrary headers) via
                    // fr.pivot.notification.config.StompAuthChannelInterceptor — an
                    // unauthenticated CONNECT is rejected and the STOMP session never established.
                    .requestMatchers("/ws/notifications/**").permitAll()
                    .anyRequest().authenticated()
                )
                // Opaque token filter runs before Spring's default UsernamePasswordAuthenticationFilter
                .addFilterBefore(tokenAuthFilter, UsernamePasswordAuthenticationFilter.class)
                // EN04.1 — runs immediately after: SecurityContextHolder is already populated
                // (or not, for anonymous requests) by the time MDC is filled in.
                .addFilterAfter(requestMdcFilter, TokenAuthenticationFilter.class);

            return http.build();
        } catch (final Exception e) {
            // Specific exception instead of propagating the generic checked Exception (Sonar S112).
            throw new IllegalStateException("Failed to build the security filter chain", e);
        }
    }

    /**
     * BCrypt password encoder at cost factor 12.
     *
     * @return configured {@link PasswordEncoder}
     */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(12);
    }

    /**
     * CORS configuration allowing the configured frontend origins.
     *
     * @return CORS configuration source
     */
    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        final CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOrigins(List.of(allowedOrigins.split(",")));
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("*"));
        // X-Request-Id (EN04.1) — correlation id echoed on every response, exposed so the
        // frontend can surface it in bug reports/support tickets without server log access.
        config.setExposedHeaders(List.of("X-New-Token", "X-Token-Expires-At", RequestMdcFilter.REQUEST_ID_HEADER));
        config.setAllowCredentials(true);
        final UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }
}

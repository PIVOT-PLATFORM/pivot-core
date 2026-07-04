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

    /**
     * Constructs the security configuration.
     *
     * @param tokenAuthFilter the opaque token validation filter
     */
    public SecurityConfig(final TokenAuthenticationFilter tokenAuthFilter) {
        this.tokenAuthFilter = tokenAuthFilter;
    }

    /**
     * Configures the security filter chain.
     *
     * <p>Public routes: actuator health/info, CORS preflight, all {@code /auth/**} endpoints.
     * All other routes require a valid opaque token validated by {@link TokenAuthenticationFilter}.
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
                    .requestMatchers("/actuator/health", "/actuator/info", "/error").permitAll()
                    .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                    .requestMatchers("/auth/**").permitAll()
                    .requestMatchers(HttpMethod.POST, "/contact").permitAll()
                    // US02.1.1 — avatar images served as a public static resource (unguessable
                    // UUID filename, see AvatarWebConfig) so a plain <img src> works with no
                    // per-request auth plumbing. GET only.
                    .requestMatchers(HttpMethod.GET, "/avatars/**").permitAll()
                    .anyRequest().authenticated()
                )
                // Opaque token filter runs before Spring's default UsernamePasswordAuthenticationFilter
                .addFilterBefore(tokenAuthFilter, UsernamePasswordAuthenticationFilter.class);

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
        config.setExposedHeaders(List.of("X-New-Token", "X-Token-Expires-At"));
        config.setAllowCredentials(true);
        final UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }
}

package fr.pivot.collaboratif.config;

import fr.pivot.collaboratif.context.CollaboratifRequestPrincipalResolver;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.List;

/**
 * Spring MVC configuration for the collaboratif module.
 *
 * <p><strong>Renamed from {@code WebMvcConfig} (EN53.2 Vague 2 modulith merge)</strong> — the
 * agilite module (EN53.1 Vague 1) already registers its own {@code
 * fr.pivot.agilite.config.WebMvcConfig} {@code @Configuration} bean; both would otherwise
 * generate the identical default Spring bean id {@code webMvcConfig} once component-scanned into
 * the same aggregated {@code pivot-core-app} context. Renaming this module's copy (rather than
 * agilite's, which merged first) resolves the collision without touching already-shipped code.
 *
 * <p>Registers the {@link CollaboratifRequestPrincipalResolver} so that controller methods
 * can declare a {@code CollaboratifRequestPrincipal} parameter that is resolved automatically
 * from the validated {@code Authorization: Bearer} token (EN08.3).
 *
 * <p><strong>CORS (EN08.3 follow-up).</strong> No REST CORS configuration existed anywhere in
 * this repo before this — only {@link CollaboratifWebSocketConfig} configured CORS, for the STOMP
 * endpoint alone. The old {@code X-Pivot-User-Id}/{@code X-Pivot-Tenant-Id} header stub already
 * required a preflight (custom headers always do), and the real bearer-token {@code Authorization}
 * header does too — without an explicit {@link CorsRegistry}, Spring routes the browser's {@code
 * OPTIONS} preflight straight to the mapped controller method instead of answering it, so the
 * response carries no {@code Access-Control-Allow-*} headers and the browser silently drops the
 * real request. Confirmed the hard way: every whiteboard E2E spec exercising a real API call
 * failed identically, board creation never completing, with only {@code OPTIONS} requests ever
 * reaching the server. Reuses {@code pivot.cors.allowed-origins} — the same property {@link
 * CollaboratifWebSocketConfig} already reads — so both configs stay in sync from one source.
 */
@Configuration
public class CollaboratifWebMvcConfig implements WebMvcConfigurer {

    private final CollaboratifRequestPrincipalResolver requestPrincipalResolver;
    private final String allowedOrigins;

    /**
     * Constructs the configuration with the shared {@link CollaboratifRequestPrincipalResolver}
     * bean.
     *
     * @param requestPrincipalResolver the argument resolver to register
     * @param allowedOrigins           comma-separated allowed origins ({@code
     *                                 pivot.cors.allowed-origins}, same property as {@link
     *                                 CollaboratifWebSocketConfig})
     */
    public CollaboratifWebMvcConfig(
            final CollaboratifRequestPrincipalResolver requestPrincipalResolver,
            @Value("${pivot.cors.allowed-origins:http://localhost:4200}") final String allowedOrigins) {
        this.requestPrincipalResolver = requestPrincipalResolver;
        this.allowedOrigins = allowedOrigins;
    }

    /**
     * Registers custom argument resolvers.
     *
     * @param resolvers the list of resolvers to add to
     */
    @Override
    public void addArgumentResolvers(final List<HandlerMethodArgumentResolver> resolvers) {
        resolvers.add(requestPrincipalResolver);
    }

    /**
     * Allows the configured origin(s) to call every {@code /api/collaboratif/**} endpoint,
     * including the {@code Authorization} header the REST layer requires (EN08.3).
     *
     * @param registry the CORS registry to configure
     */
    @Override
    public void addCorsMappings(final CorsRegistry registry) {
        registry.addMapping("/**")
                .allowedOrigins(allowedOrigins.split(","))
                .allowedMethods("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS")
                .allowedHeaders("Authorization", "Content-Type")
                .allowCredentials(false)
                .maxAge(3600);
    }
}

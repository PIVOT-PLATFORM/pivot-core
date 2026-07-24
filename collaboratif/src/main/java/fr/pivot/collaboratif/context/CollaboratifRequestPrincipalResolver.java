package fr.pivot.collaboratif.context;

import fr.pivot.core.auth.AuthenticatedPrincipal;
import fr.pivot.core.auth.AuthenticatedPrincipalResolver;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;
import org.springframework.web.server.ResponseStatusException;

/**
 * Resolves a {@link CollaboratifRequestPrincipal} from the {@code Authorization: Bearer} HTTP
 * request header, delegating the actual token validation to the injected {@link
 * AuthenticatedPrincipalResolver} bean (EN08.3, ADR-022).
 *
 * <p><strong>Renamed from {@code RequestPrincipalResolver} (EN53.2 Vague 2 modulith merge).</strong>
 * The agilite module (EN53.1 Vague 1) already registers its own {@code
 * fr.pivot.agilite.context.RequestPrincipalResolver} {@code @Component} bean. Spring's default
 * annotation-based bean-name generator derives a bean's id from its simple class name
 * (decapitalized) when no explicit name is given — both classes would otherwise generate the
 * identical bean id {@code requestPrincipalResolver} once component-scanned into the same
 * aggregated {@code pivot-core-app} context, which fails context startup with a {@code
 * ConflictingBeanDefinitionException}. Renaming this module's copy (rather than agilite's, which
 * merged first) resolves the collision without touching already-shipped code.
 *
 * <p><strong>Concrete implementation (EN53.2 Vague 2 modulith merge).</strong> In production, this
 * module is aggregated into {@code pivot-core-app}'s single Spring context, where {@code
 * fr.pivot.auth.service.TokenService} (the shell's own opaque-token service) is the sole {@link
 * AuthenticatedPrincipalResolver} bean — this class was already coded against the shared
 * interface, never against a concrete implementation, so it transparently authenticates through
 * the shell's real token validation with no code change. This module's own <em>isolated</em>
 * {@code @SpringBootTest} suite (which has no dependency on {@code pivot-core-app}'s classes)
 * instead relies on a local test-only double, {@code
 * fr.pivot.collaboratif.testsupport.auth.TestAuthenticatedPrincipalResolver} — see that class's
 * Javadoc. Before this merge, a production duplicate ({@code
 * fr.pivot.collaboratif.auth.TokenValidationService}) played that role in this repo's own
 * standalone deployment, alongside a {@code fr.pivot.collaboratif.config.SecurityConfig} that
 * permitted every request through Spring Security unconditionally (a workaround required only
 * because {@code pivot-core-starter} 0.27.1 transitively pulled in {@code
 * spring-boot-starter-security} onto this module's classpath); both have been removed, not
 * replaced, in production — the shell's own {@code fr.pivot.config.SecurityConfig} owns the
 * aggregated app's single security filter chain.
 *
 * <p>Returns HTTP 401 Unauthorized — with a generic message, never leaking whether the header
 * was missing, malformed, or the token itself was unknown/expired/revoked/tenant-deactivated/
 * user-deactivated — if the {@code Authorization} header is absent, does not use a
 * case-insensitive {@code Bearer } prefix, or the resolved token is rejected.
 */
@Component
public class CollaboratifRequestPrincipalResolver implements HandlerMethodArgumentResolver {

    private static final String AUTHORIZATION_HEADER = "Authorization";
    private static final String BEARER_PREFIX = "Bearer ";
    private static final String UNAUTHORIZED_MESSAGE = "Unauthorized";

    private final AuthenticatedPrincipalResolver principalResolver;

    /**
     * Constructs the resolver with the shared {@link AuthenticatedPrincipalResolver} bean.
     *
     * @param principalResolver the bean that validates a raw bearer token against {@code
     *                          public.access_tokens}/{@code public.users}/{@code public.tenants}
     */
    public CollaboratifRequestPrincipalResolver(final AuthenticatedPrincipalResolver principalResolver) {
        this.principalResolver = principalResolver;
    }

    /**
     * Returns {@code true} if the parameter type is {@link CollaboratifRequestPrincipal}.
     *
     * @param parameter the method parameter to check
     * @return {@code true} when the parameter type matches
     */
    @Override
    public boolean supportsParameter(final MethodParameter parameter) {
        return CollaboratifRequestPrincipal.class.equals(parameter.getParameterType());
    }

    /**
     * Resolves the {@link CollaboratifRequestPrincipal} from the {@code Authorization} header.
     *
     * @param parameter     the method parameter being resolved
     * @param mavContainer  the model and view container (unused)
     * @param webRequest    the current web request
     * @param binderFactory the binder factory (unused)
     * @return a {@link CollaboratifRequestPrincipal} populated from the validated bearer token
     * @throws ResponseStatusException with HTTP 401 if the header is missing/malformed or the
     *     token is rejected
     */
    @Override
    public Object resolveArgument(
            final MethodParameter parameter,
            final ModelAndViewContainer mavContainer,
            final NativeWebRequest webRequest,
            final WebDataBinderFactory binderFactory) {

        final HttpServletRequest request = webRequest.getNativeRequest(HttpServletRequest.class);
        if (request == null) {
            throw unauthorized();
        }

        final String rawToken = extractBearerToken(request.getHeader(AUTHORIZATION_HEADER));
        if (rawToken == null) {
            throw unauthorized();
        }

        final AuthenticatedPrincipal principal = principalResolver.resolve(rawToken)
                .orElseThrow(CollaboratifRequestPrincipalResolver::unauthorized);

        return new CollaboratifRequestPrincipal(principal.userId(), principal.tenantId(), principal.role());
    }

    /**
     * Extracts the raw token from an {@code Authorization} header value, requiring a
     * case-insensitive {@code Bearer } prefix.
     *
     * @param authorizationHeader the raw {@code Authorization} header value, may be {@code null}
     * @return the raw token, or {@code null} if the header is absent, malformed, or the prefix
     *     does not match
     */
    private static String extractBearerToken(final String authorizationHeader) {
        if (authorizationHeader == null
                || authorizationHeader.length() <= BEARER_PREFIX.length()
                || !authorizationHeader.regionMatches(true, 0, BEARER_PREFIX, 0, BEARER_PREFIX.length())) {
            return null;
        }
        final String token = authorizationHeader.substring(BEARER_PREFIX.length()).trim();
        return token.isEmpty() ? null : token;
    }

    /**
     * Builds the generic 401 thrown for every rejection case — never leaks the reason.
     *
     * @return a {@link ResponseStatusException} carrying HTTP 401 and a generic message
     */
    private static ResponseStatusException unauthorized() {
        return new ResponseStatusException(HttpStatus.UNAUTHORIZED, UNAUTHORIZED_MESSAGE);
    }
}

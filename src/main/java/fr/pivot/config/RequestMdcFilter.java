package fr.pivot.config;

import fr.pivot.auth.entity.User;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

/**
 * Populates the SLF4J {@link MDC} with per-request correlation data (EN04.1) so every log line
 * emitted while handling a request — by this filter, downstream {@code HandlerInterceptor}s,
 * controllers or services — is enriched with {@code requestId}/{@code tenantId}/{@code userId}
 * once serialized by the JSON encoder configured in {@code logback-spring.xml}.
 *
 * <p>Registered via {@link SecurityConfig#filterChain} with
 * {@code addFilterAfter(this, TokenAuthenticationFilter.class)} — it runs immediately after
 * {@link TokenAuthenticationFilter}, so {@link SecurityContextHolder} is already populated with
 * the authenticated {@link User} (in {@code Authentication#getDetails()}, see
 * {@link fr.pivot.modules.api.ModuleController#buildTenantContext(User)} for the same
 * extraction pattern) by the time this filter reads it — no re-validation of the bearer token,
 * no new resolution logic invented.
 *
 * <p><b>requestId</b>: read from the incoming {@value #REQUEST_ID_HEADER} header when the
 * caller already has one (e.g. a request re-issued by the frontend after a retry, or a value
 * threaded through by an upstream reverse proxy), otherwise a fresh random UUID is generated.
 * Echoed back on the response (same header) and exposed cross-origin (see
 * {@link SecurityConfig#corsConfigurationSource()}) so the frontend can surface it in bug
 * reports/support tickets without needing server-side log access. A caller-supplied value is
 * untrusted input: CR/LF are stripped (CWE-117 log forging — same defense already applied to
 * other untrusted values via {@code sanitizeForLog} in {@link fr.pivot.modules.api.ModuleController}
 * / {@link fr.pivot.core.modules.ModuleActivationService}, needed here too since MDC feeds both
 * the JSON encoder and, on the {@code test} profile, a plain-text pattern) and the value is capped
 * to {@value #MAX_REQUEST_ID_LENGTH} characters before it ever reaches MDC or the response header.
 *
 * <p><b>tenantId</b> / <b>userId</b>: the numeric database primary keys ({@code public.tenants.id}
 * / {@code public.users.id}) — never the email, name or raw/hashed token. This matches the
 * "no personal data in logs" requirement without hashing: every existing structured log
 * statement in this codebase (see {@code TokenService}, {@code AuditService} call sites,
 * {@code ModuleActivationService}, {@code ModuleController}…) already logs this same numeric id
 * in the clear — it is an internal surrogate key, not personally identifying on its own, unlike
 * the AC's explicit "no email/password/token" examples. Introducing a SHA-256 hash here only for
 * the MDC copy of the same value that's already logged unhashed everywhere else would add
 * inconsistency (two different representations of "the current user" across log lines for the
 * same request) without any actual confidentiality gain.
 *
 * <p>Absent for anonymous/unauthenticated requests (e.g. {@code /auth/login}) — {@code tenantId}
 * and {@code userId} are simply omitted from the MDC (and therefore from the JSON log line)
 * rather than logged as a placeholder.
 *
 * <p><b>WebSocket/STOMP</b>: as of this enabler, no STOMP {@code @MessageMapping} handler or
 * {@code ChannelInterceptor} exists yet in this codebase (only the {@code spring-boot-starter
 * -websocket} dependency is present, unused) — there is nothing to instrument. The same
 * requestId/userId (+ {@code boardId}/{@code sessionId}) MDC pattern described here is intended
 * to be applied via a {@code ChannelInterceptor} on the STOMP inbound channel when the
 * whiteboard/collaborative WebSocket work (EN08.1 or equivalent) actually introduces handlers.
 *
 * <p>MDC is fully cleared in a {@code finally} block after the request completes (success,
 * client abort or unhandled exception) so no value ever leaks into a later, unrelated request
 * reusing the same pooled worker thread.
 */
@Component
public class RequestMdcFilter extends OncePerRequestFilter {

    /** Header used both to read a caller-supplied request id and to echo it back on the response. */
    public static final String REQUEST_ID_HEADER = "X-Request-Id";

    /** Upper bound applied to a caller-supplied requestId — defense against log/header abuse. */
    static final int MAX_REQUEST_ID_LENGTH = 128;

    static final String MDC_REQUEST_ID = "requestId";
    static final String MDC_TENANT_ID = "tenantId";
    static final String MDC_USER_ID = "userId";

    @Override
    protected void doFilterInternal(final HttpServletRequest request,
                                     final HttpServletResponse response,
                                     final FilterChain chain) throws ServletException, IOException {
        final String requestId = resolveRequestId(request);
        try {
            MDC.put(MDC_REQUEST_ID, requestId);
            response.setHeader(REQUEST_ID_HEADER, requestId);
            populateAuthenticatedContext();
            chain.doFilter(request, response);
        } finally {
            // Full clear (not just our 3 keys) — thread-pool safety: guarantees nothing set by
            // this or any nested call leaks into the next request served by the same thread.
            MDC.clear();
        }
    }

    private static String resolveRequestId(final HttpServletRequest request) {
        final String incoming = request.getHeader(REQUEST_ID_HEADER);
        if (incoming == null || incoming.isBlank()) {
            return UUID.randomUUID().toString();
        }
        return sanitize(incoming);
    }

    /**
     * Neutralizes CR/LF (CWE-117 log forging) and caps the length of a caller-supplied value
     * before it reaches MDC (and therefore log lines) or the response header. Mirrors the
     * {@code sanitizeForLog} helper already used elsewhere in this codebase for other untrusted
     * request-derived strings.
     */
    private static String sanitize(final String value) {
        final String stripped = value.replaceAll("[\r\n]", "_");
        return stripped.length() > MAX_REQUEST_ID_LENGTH ? stripped.substring(0, MAX_REQUEST_ID_LENGTH) : stripped;
    }

    private static void populateAuthenticatedContext() {
        final Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getDetails() instanceof User user)) {
            return;
        }
        if (user.getId() != null) {
            MDC.put(MDC_USER_ID, user.getId().toString());
        }
        if (user.getTenant() != null && user.getTenant().getId() != null) {
            MDC.put(MDC_TENANT_ID, user.getTenant().getId().toString());
        }
    }
}

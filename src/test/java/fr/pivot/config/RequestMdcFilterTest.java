package fr.pivot.config;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import fr.pivot.auth.entity.User;
import fr.pivot.tenant.entity.Tenant;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Map;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link RequestMdcFilter} (EN04.1).
 *
 * <p>Covers: {@code requestId} generation vs. reuse of the incoming {@code X-Request-Id} header,
 * the response header echo, MDC content while the downstream chain executes — captured with a
 * Logback {@link ListAppender}, per {@code skill-observability}/EN04.1's TU requirement — for
 * both the authenticated and anonymous paths, and MDC clearing after the request on both the
 * success and exception paths (thread-pool leakage prevention).
 */
@ExtendWith(MockitoExtension.class)
class RequestMdcFilterTest {

    private static final Pattern UUID_PATTERN = Pattern.compile(
            "^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$");

    @Mock private HttpServletRequest request;
    @Mock private HttpServletResponse response;
    @Mock private FilterChain chain;

    private RequestMdcFilter filter;

    @BeforeEach
    void setUp() {
        filter = new RequestMdcFilter();
        SecurityContextHolder.clearContext();
        MDC.clear();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
        MDC.clear();
    }

    // ----------------------------------------------------------------
    // requestId — generation / reuse / echo on the response
    // ----------------------------------------------------------------

    @Test
    void doFilter_generatesUuidRequestId_whenNoIncomingHeader() throws Exception {
        when(request.getHeader(RequestMdcFilter.REQUEST_ID_HEADER)).thenReturn(null);

        filter.doFilterInternal(request, response, chain);

        assertThat(UUID_PATTERN.matcher(capturedResponseRequestId()).matches()).isTrue();
    }

    @Test
    void doFilter_generatesUuidRequestId_whenIncomingHeaderBlank() throws Exception {
        when(request.getHeader(RequestMdcFilter.REQUEST_ID_HEADER)).thenReturn("   ");

        filter.doFilterInternal(request, response, chain);

        assertThat(UUID_PATTERN.matcher(capturedResponseRequestId()).matches()).isTrue();
    }

    @Test
    void doFilter_reusesIncomingRequestId_whenHeaderPresent() throws Exception {
        when(request.getHeader(RequestMdcFilter.REQUEST_ID_HEADER)).thenReturn("caller-supplied-id");

        filter.doFilterInternal(request, response, chain);

        verify(response).setHeader(RequestMdcFilter.REQUEST_ID_HEADER, "caller-supplied-id");
    }

    @Test
    void doFilter_stripsCrLf_fromIncomingRequestId() throws Exception {
        // CWE-117 (log forging): a malicious X-Request-Id must not be able to inject fake log
        // lines, nor split the response into extra headers.
        when(request.getHeader(RequestMdcFilter.REQUEST_ID_HEADER))
                .thenReturn("abc\r\nX-Injected: evil\ndef");

        filter.doFilterInternal(request, response, chain);

        final String sanitized = capturedResponseRequestId();
        assertThat(sanitized).doesNotContain("\r").doesNotContain("\n");
        assertThat(sanitized).isEqualTo("abc__X-Injected: evil_def");
    }

    @Test
    void doFilter_truncatesExcessivelyLongIncomingRequestId() throws Exception {
        final String tooLong = "a".repeat(500);
        when(request.getHeader(RequestMdcFilter.REQUEST_ID_HEADER)).thenReturn(tooLong);

        filter.doFilterInternal(request, response, chain);

        assertThat(capturedResponseRequestId()).hasSize(RequestMdcFilter.MAX_REQUEST_ID_LENGTH);
    }

    @Test
    void doFilter_alwaysInvokesChain() throws Exception {
        when(request.getHeader(RequestMdcFilter.REQUEST_ID_HEADER)).thenReturn(null);

        filter.doFilterInternal(request, response, chain);

        verify(chain).doFilter(request, response);
    }

    // ----------------------------------------------------------------
    // MDC content during the request — captured via a Logback ListAppender
    // ----------------------------------------------------------------

    @Test
    void doFilter_populatesMdc_whenAuthenticatedWithTenant() throws Exception {
        when(request.getHeader(RequestMdcFilter.REQUEST_ID_HEADER)).thenReturn("req-123");
        setAuthentication(buildUser(7L, 42L));
        chainLogsMarkerDuringExecution();

        final Map<String, String> mdc = mdcDuringChainExecution();

        assertThat(mdc).containsEntry("requestId", "req-123");
        assertThat(mdc).containsEntry("userId", "7");
        assertThat(mdc).containsEntry("tenantId", "42");
    }

    @Test
    void doFilter_omitsUserAndTenant_whenUnauthenticated() throws Exception {
        when(request.getHeader(RequestMdcFilter.REQUEST_ID_HEADER)).thenReturn("req-anon");
        // No authentication in SecurityContextHolder — e.g. /auth/login, /auth/register.
        chainLogsMarkerDuringExecution();

        final Map<String, String> mdc = mdcDuringChainExecution();

        assertThat(mdc).containsEntry("requestId", "req-anon");
        assertThat(mdc).doesNotContainKey("userId");
        assertThat(mdc).doesNotContainKey("tenantId");
    }

    @Test
    void doFilter_omitsUserAndTenant_whenAuthDetailsNotAUser() throws Exception {
        // e.g. a future OIDC-only path where getDetails() isn't the local User entity.
        when(request.getHeader(RequestMdcFilter.REQUEST_ID_HEADER)).thenReturn("req-oidc");
        final UsernamePasswordAuthenticationToken auth =
                new UsernamePasswordAuthenticationToken("principal", "credentials");
        auth.setDetails("not-a-user-object");
        SecurityContextHolder.getContext().setAuthentication(auth);
        chainLogsMarkerDuringExecution();

        final Map<String, String> mdc = mdcDuringChainExecution();

        assertThat(mdc).doesNotContainKey("userId");
        assertThat(mdc).doesNotContainKey("tenantId");
    }

    @Test
    void doFilter_omitsTenantId_whenUserHasNoTenant() throws Exception {
        when(request.getHeader(RequestMdcFilter.REQUEST_ID_HEADER)).thenReturn("req-no-tenant");
        final User user = mock(User.class);
        when(user.getId()).thenReturn(9L);
        when(user.getTenant()).thenReturn(null);
        setAuthentication(user);
        chainLogsMarkerDuringExecution();

        final Map<String, String> mdc = mdcDuringChainExecution();

        assertThat(mdc).containsEntry("userId", "9");
        assertThat(mdc).doesNotContainKey("tenantId");
    }

    // ----------------------------------------------------------------
    // MDC cleared after the request — success and exception paths
    // ----------------------------------------------------------------

    @Test
    void doFilter_clearsMdc_afterSuccessfulChain() throws Exception {
        when(request.getHeader(RequestMdcFilter.REQUEST_ID_HEADER)).thenReturn("req-cleanup");
        setAuthentication(buildUser(1L, 2L));

        filter.doFilterInternal(request, response, chain);

        assertThat(MDC.get("requestId")).isNull();
        assertThat(MDC.get("userId")).isNull();
        assertThat(MDC.get("tenantId")).isNull();
    }

    @Test
    void doFilter_clearsMdc_evenWhenChainThrows() throws Exception {
        when(request.getHeader(RequestMdcFilter.REQUEST_ID_HEADER)).thenReturn("req-boom");
        doThrow(new IllegalStateException("downstream failure")).when(chain).doFilter(request, response);

        assertThatThrownBy(() -> filter.doFilterInternal(request, response, chain))
                .isInstanceOf(IllegalStateException.class);

        assertThat(MDC.get("requestId")).isNull();
    }

    // ----------------------------------------------------------------
    // Helpers
    // ----------------------------------------------------------------

    private String capturedResponseRequestId() {
        final ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(response).setHeader(eq(RequestMdcFilter.REQUEST_ID_HEADER), captor.capture());
        return captor.getValue();
    }

    /** Stubs the mocked {@link FilterChain} to emit one log line while it "runs", so the MDC
     *  snapshot active at that moment can be captured via a {@link ListAppender}. */
    private void chainLogsMarkerDuringExecution() throws Exception {
        doAnswer(invocation -> {
            LoggerFactory.getLogger(RequestMdcFilterTest.class).info("marker-during-chain");
            return null;
        }).when(chain).doFilter(request, response);
    }

    /** Runs the filter and returns the MDC property map captured on the marker log line emitted
     *  by {@link #chainLogsMarkerDuringExecution()} — i.e. the MDC state visible mid-request. */
    private Map<String, String> mdcDuringChainExecution() throws Exception {
        final Logger logger = (Logger) LoggerFactory.getLogger(RequestMdcFilterTest.class);
        final ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            filter.doFilterInternal(request, response, chain);
        } finally {
            logger.detachAppender(appender);
        }
        assertThat(appender.list).hasSize(1);
        return appender.list.get(0).getMDCPropertyMap();
    }

    private static User buildUser(final Long userId, final Long tenantId) {
        final User user = mock(User.class);
        when(user.getId()).thenReturn(userId);
        final Tenant tenant = mock(Tenant.class);
        when(tenant.getId()).thenReturn(tenantId);
        when(user.getTenant()).thenReturn(tenant);
        return user;
    }

    private static void setAuthentication(final User user) {
        final UsernamePasswordAuthenticationToken auth =
                new UsernamePasswordAuthenticationToken(user.getId(), null);
        auth.setDetails(user);
        SecurityContextHolder.getContext().setAuthentication(auth);
    }
}

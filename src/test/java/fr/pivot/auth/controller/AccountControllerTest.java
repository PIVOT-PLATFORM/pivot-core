package fr.pivot.auth.controller;

import fr.pivot.auth.dto.AuthResponse;
import fr.pivot.auth.dto.ChangePasswordRequest;
import fr.pivot.auth.dto.LoginResult;
import fr.pivot.auth.entity.User;
import fr.pivot.auth.service.AccountPasswordService;
import fr.pivot.config.CookieHelper;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link AccountController} — HTTP concerns: identity extraction from the
 * security context, cookie management, and delegation to {@link AccountPasswordService}.
 *
 * <p>Traceability: "identité extraite du token porteur uniquement" —
 * {@code changePassword_returns401_whenAuthDetailsNotUser}.
 */
@ExtendWith(MockitoExtension.class)
class AccountControllerTest {

    @Mock
    private AccountPasswordService accountPasswordService;

    private AccountController controller;
    private MockHttpServletRequest req;
    private MockHttpServletResponse res;

    @BeforeEach
    void setUp() {
        controller = new AccountController(
            accountPasswordService, new CookieHelper("pivot_session", true));
        req = new MockHttpServletRequest();
        req.setRemoteAddr("9.9.9.9");
        req.addHeader("User-Agent", "JUnit");
        res = new MockHttpServletResponse();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private AuthResponse.UserInfo userInfo() {
        return new AuthResponse.UserInfo(7L, "u@x.com", "A", "B", "ROLE_USER", true, 1L, "pivot-saas");
    }

    private void authenticateAs(final Long userId) {
        final User user = mock(User.class);
        when(user.getId()).thenReturn(userId);
        final UsernamePasswordAuthenticationToken auth =
            new UsernamePasswordAuthenticationToken(userId, null);
        auth.setDetails(user);
        SecurityContextHolder.getContext().setAuthentication(auth);
    }

    @Test
    void changePassword_setsCookieAndReturnsAuthResponse_onSuccess() {
        authenticateAs(7L);
        when(accountPasswordService.changePassword(eq(7L), any(), anyString(), anyString()))
            .thenReturn(LoginResult.success("new-tok", 123L, 3600L, userInfo()));

        final ResponseEntity<AuthResponse> resp = controller.changePassword(
            new ChangePasswordRequest("Old1!aaaaaaa", "New1!aaaaaaa"), req, res);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody().accessToken()).isEqualTo("new-tok");
        final Cookie cookie = res.getCookie("pivot_session");
        assertThat(cookie).isNotNull();
        assertThat(cookie.getValue()).isEqualTo("new-tok");
        assertThat(cookie.isHttpOnly()).isTrue();
        assertThat(cookie.getMaxAge()).isEqualTo(3600);
    }

    @Test
    void changePassword_delegatesWithIpAndUserAgent_ignoringXForwardedFor() {
        // Security fix mirrored from AuthController: the real client IP is resolved by
        // Tomcat's RemoteIpValve, never a client-controlled header.
        authenticateAs(7L);
        req.addHeader("X-Forwarded-For", "1.1.1.1, 2.2.2.2");
        when(accountPasswordService.changePassword(eq(7L), any(), anyString(), anyString()))
            .thenReturn(LoginResult.success("new-tok", 123L, 3600L, userInfo()));

        controller.changePassword(new ChangePasswordRequest("Old1!aaaaaaa", "New1!aaaaaaa"), req, res);

        verify(accountPasswordService).changePassword(eq(7L), any(), eq("9.9.9.9"), eq("JUnit"));
    }

    @Test
    void changePassword_returns401_whenAuthDetailsNotUser() {
        final UsernamePasswordAuthenticationToken auth =
            new UsernamePasswordAuthenticationToken("principal", "credentials");
        auth.setDetails("not-a-user-object");
        SecurityContextHolder.getContext().setAuthentication(auth);

        final ResponseEntity<AuthResponse> resp = controller.changePassword(
            new ChangePasswordRequest("Old1!aaaaaaa", "New1!aaaaaaa"), req, res);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        verifyNoInteractions(accountPasswordService);
        assertThat(res.getCookie("pivot_session")).isNull();
    }

    @Test
    void changePassword_returns401_whenAuthDetailsNull() {
        final UsernamePasswordAuthenticationToken auth =
            new UsernamePasswordAuthenticationToken("principal", "credentials");
        auth.setDetails(null);
        SecurityContextHolder.getContext().setAuthentication(auth);

        final ResponseEntity<AuthResponse> resp = controller.changePassword(
            new ChangePasswordRequest("Old1!aaaaaaa", "New1!aaaaaaa"), req, res);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        verify(accountPasswordService, never()).changePassword(any(), any(), anyString(), anyString());
    }
}

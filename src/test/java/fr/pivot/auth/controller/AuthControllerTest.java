package fr.pivot.auth.controller;

import fr.pivot.auth.dto.AuthResponse;
import fr.pivot.auth.dto.DeviceOtpRequest;
import fr.pivot.auth.dto.ForgotPasswordRequest;
import fr.pivot.auth.dto.LoginRequest;
import fr.pivot.auth.dto.LoginResult;
import fr.pivot.auth.dto.RegisterRequest;
import fr.pivot.auth.dto.ResetPasswordRequest;
import fr.pivot.auth.service.PasswordService;
import fr.pivot.auth.service.RegistrationService;
import fr.pivot.auth.service.SessionService;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link AuthController} — HTTP concerns: cookie management,
 * IP extraction, status codes and service delegation.
 */
@ExtendWith(MockitoExtension.class)
class AuthControllerTest {

    @Mock private RegistrationService registrationService;
    @Mock private SessionService sessionService;
    @Mock private PasswordService passwordService;

    private AuthController controller;
    private MockHttpServletRequest req;
    private MockHttpServletResponse res;

    @BeforeEach
    void setUp() {
        controller = new AuthController(registrationService, sessionService, passwordService,
            "pivot_session", true);
        req = new MockHttpServletRequest();
        req.setRemoteAddr("9.9.9.9");
        req.addHeader("User-Agent", "JUnit");
        res = new MockHttpServletResponse();
    }

    private AuthResponse.UserInfo userInfo() {
        return new AuthResponse.UserInfo(1L, "u@x.com", "A", "B", "ROLE_USER", true, 1L, "pivot-saas");
    }

    @Test
    void register_delegatesAndReturnsMessage() {
        final Map<String, String> body = controller.register(
            new RegisterRequest("u@x.com", "password1", "A", "B"), req);

        assertThat(body).containsKey("message");
        verify(registrationService).register(any(), eq("9.9.9.9"), eq("JUnit"));
    }

    @Test
    void register_usesXForwardedFor_whenPresent() {
        req.addHeader("X-Forwarded-For", "1.1.1.1, 2.2.2.2");

        controller.register(new RegisterRequest("u@x.com", "password1", "A", "B"), req);

        verify(registrationService).register(any(), eq("1.1.1.1"), anyString());
    }

    @Test
    void verifyEmail_returnsOk() {
        final ResponseEntity<Map<String, String>> resp = controller.verifyEmail("tok", req);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        verify(registrationService).verifyEmail(eq("tok"), anyString(), anyString());
    }

    @Test
    void resendVerification_returnsMessage() {
        final Map<String, String> body = controller.resendVerification("u@x.com", req);

        assertThat(body).containsKey("message");
        verify(registrationService).resendVerification(eq("u@x.com"), anyString(), anyString());
    }

    @Test
    void login_setsCookieAndReturnsAuthResponse_onSuccess() {
        when(sessionService.login(any(), anyString(), anyString()))
            .thenReturn(LoginResult.success("tok", 123L, 3600L, userInfo()));

        final ResponseEntity<AuthResponse> resp = controller.login(
            new LoginRequest("u@x.com", "pw", null, null, false), req, res);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody().accessToken()).isEqualTo("tok");
        final Cookie cookie = res.getCookie("pivot_session");
        assertThat(cookie).isNotNull();
        assertThat(cookie.getValue()).isEqualTo("tok");
        assertThat(cookie.isHttpOnly()).isTrue();
        assertThat(cookie.getMaxAge()).isEqualTo(3600);
    }

    @Test
    void login_returns202WithHeader_whenDeviceVerificationRequired() {
        when(sessionService.login(any(), anyString(), anyString()))
            .thenReturn(LoginResult.requiresDeviceVerification("fp"));

        final ResponseEntity<AuthResponse> resp = controller.login(
            new LoginRequest("u@x.com", "pw", "fp", null, false), req, res);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(resp.getHeaders().getFirst("X-Device-Verification-Required")).isEqualTo("true");
        assertThat(res.getCookie("pivot_session")).isNull();
    }

    @Test
    void verifyDevice_setsCookieAndReturnsResponse() {
        when(sessionService.verifyDeviceOtp(any(), anyString(), anyString()))
            .thenReturn(LoginResult.success("tok2", 1L, 60L, userInfo()));

        final ResponseEntity<AuthResponse> resp = controller.verifyDevice(
            new DeviceOtpRequest("fp", "123456", "Chrome", false), req, res);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(res.getCookie("pivot_session").getValue()).isEqualTo("tok2");
    }

    @Test
    void refresh_returns401_whenNoCookie() {
        final ResponseEntity<AuthResponse> resp = controller.refresh(req, res);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void refresh_returnsResponse_whenCookiePresent() {
        req.setCookies(new Cookie("pivot_session", "raw-cookie"));
        when(sessionService.restoreSession(eq("raw-cookie"), anyString(), anyString()))
            .thenReturn(LoginResult.success("raw-cookie", 1L, 60L, userInfo()));

        final ResponseEntity<AuthResponse> resp = controller.refresh(req, res);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody().accessToken()).isEqualTo("raw-cookie");
    }

    @Test
    void logout_clearsCookieAndDelegates() {
        req.setCookies(new Cookie("pivot_session", "raw-cookie"));

        controller.logout(req, res);

        verify(sessionService).logout("raw-cookie");
        final Cookie cleared = res.getCookie("pivot_session");
        assertThat(cleared).isNotNull();
        assertThat(cleared.getMaxAge()).isZero();
    }

    @Test
    void logout_handlesNoCookie() {
        controller.logout(req, res);

        verify(sessionService).logout(null);
    }

    @Test
    void forgotPassword_returnsMessage() {
        final Map<String, String> body = controller.forgotPassword(
            new ForgotPasswordRequest("u@x.com"), req);

        assertThat(body).containsKey("message");
        verify(passwordService).forgotPassword(any(), anyString(), anyString());
    }

    @Test
    void resetPassword_returnsOk() {
        final ResponseEntity<Map<String, String>> resp = controller.resetPassword(
            new ResetPasswordRequest("tok", "password1"), req);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        verify(passwordService).resetPassword(any(), anyString(), anyString());
    }
}

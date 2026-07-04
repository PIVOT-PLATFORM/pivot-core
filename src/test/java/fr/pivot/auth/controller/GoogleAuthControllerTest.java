package fr.pivot.auth.controller;

import fr.pivot.auth.dto.AuthResponse;
import fr.pivot.auth.dto.GoogleAuthRequest;
import fr.pivot.auth.service.GoogleAuthService;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link GoogleAuthController} — cookie issuance and delegation.
 */
@ExtendWith(MockitoExtension.class)
class GoogleAuthControllerTest {

    @Mock private GoogleAuthService googleAuthService;

    private GoogleAuthController controller;
    private MockHttpServletRequest req;
    private MockHttpServletResponse res;

    @BeforeEach
    void setUp() {
        controller = new GoogleAuthController(googleAuthService, new fr.pivot.config.CookieHelper("pivot_session", true));
        req = new MockHttpServletRequest();
        req.setRemoteAddr("9.9.9.9");
        req.addHeader("User-Agent", "JUnit");
        res = new MockHttpServletResponse();
    }

    @Test
    void authenticate_setsCookieAndReturnsResponse() {
        final AuthResponse.UserInfo ui =
                new AuthResponse.UserInfo(1L, "u@x.com", "A", "B", "ROLE_USER", true, 1L, "pivot-saas", "fr");
        when(googleAuthService.authenticate(any(), anyString(), anyString()))
            .thenReturn(new GoogleAuthService.GoogleLoginResult("g-tok", 123L, 3600, ui, true));

        final ResponseEntity<AuthResponse> resp = controller.authenticate(
            new GoogleAuthRequest("id-token", "fp", "Chrome"), req, res);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody().accessToken()).isEqualTo("g-tok");
        final Cookie cookie = res.getCookie("pivot_session");
        assertThat(cookie).isNotNull();
        assertThat(cookie.getValue()).isEqualTo("g-tok");
        assertThat(cookie.getMaxAge()).isEqualTo(3600);
    }

    @Test
    void authenticate_ignoresXForwardedFor_usesRemoteAddr() {
        // X-Forwarded-For is no longer trusted in app code (RemoteIpValve handles it at the
        // container, trusted-proxy aware). A spoofed header must not change the resolved IP.
        req.addHeader("X-Forwarded-For", "1.1.1.1, 2.2.2.2");
        final AuthResponse.UserInfo ui =
                new AuthResponse.UserInfo(1L, "u@x.com", "A", "B", "ROLE_USER", true, 1L, "s", "fr");
        when(googleAuthService.authenticate(any(), eq("9.9.9.9"), anyString()))
            .thenReturn(new GoogleAuthService.GoogleLoginResult("g-tok", 1L, 60, ui, false));

        final ResponseEntity<AuthResponse> resp = controller.authenticate(
            new GoogleAuthRequest("id-token", null, null), req, res);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
    }
}

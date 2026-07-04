package fr.pivot.auth.controller;

import fr.pivot.auth.dto.AuthResponse;
import fr.pivot.auth.dto.OidcExchangeRequest;
import fr.pivot.auth.service.OidcAuthService;
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
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link OidcAuthController} — config lookup, exchange cookie issuance.
 */
@ExtendWith(MockitoExtension.class)
class OidcAuthControllerTest {

    @Mock private OidcAuthService oidcAuthService;

    private OidcAuthController controller;
    private MockHttpServletRequest req;
    private MockHttpServletResponse res;

    @BeforeEach
    void setUp() {
        controller = new OidcAuthController(oidcAuthService, new fr.pivot.config.CookieHelper("pivot_session", true));
        req = new MockHttpServletRequest();
        req.setRemoteAddr("9.9.9.9");
        req.addHeader("User-Agent", "JUnit");
        res = new MockHttpServletResponse();
    }

    @Test
    void getConfig_delegatesToService() {
        final OidcAuthService.OidcClientConfig cfg =
            new OidcAuthService.OidcClientConfig("https://idp", "client-1", "openid email");
        when(oidcAuthService.getClientConfig("acme")).thenReturn(cfg);

        assertThat(controller.getConfig("acme")).isEqualTo(cfg);
    }

    @Test
    void exchange_setsCookieAndReturnsResponse() {
        final AuthResponse.UserInfo ui =
                new AuthResponse.UserInfo(1L, "u@x.com", "A", "B", "ROLE_USER", true, 1L, "acme", "fr");
        when(oidcAuthService.exchange(any(), anyString(), anyString()))
            .thenReturn(new OidcAuthService.OidcLoginResult("o-tok", 123L, 3600, ui));

        final ResponseEntity<AuthResponse> resp = controller.exchange(
            new OidcExchangeRequest("acme", "access-token", "fp", "Chrome"), req, res);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody().accessToken()).isEqualTo("o-tok");
        final Cookie cookie = res.getCookie("pivot_session");
        assertThat(cookie).isNotNull();
        assertThat(cookie.getValue()).isEqualTo("o-tok");
    }
}

package fr.pivot.account.controller;

import fr.pivot.account.dto.AccountDeletionCancelRequest;
import fr.pivot.account.dto.AccountDeletionRequestDto;
import fr.pivot.account.dto.AccountDeletionResponseDto;
import fr.pivot.account.entity.DeletionConfirmationMethod;
import fr.pivot.account.service.AccountDeletionService;
import fr.pivot.auth.entity.User;
import fr.pivot.config.CookieHelper;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.server.ResponseStatusException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link AccountDeletionController} (US02.2.4) — HTTP-layer concerns only:
 * identity extraction from the security context, delegation to {@link AccountDeletionService},
 * and the 401 rejection path shared with every other {@code /account/*} controller.
 */
@ExtendWith(MockitoExtension.class)
class AccountDeletionControllerTest {

    private static final String CLIENT_IP = "127.0.0.1";

    @Mock private AccountDeletionService accountDeletionService;
    @Mock private CookieHelper cookieHelper;

    private AccountDeletionController controller;
    private HttpServletRequest request;

    @BeforeEach
    void setUp() {
        controller = new AccountDeletionController(accountDeletionService, cookieHelper);
        request = mock(HttpServletRequest.class);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    // ---------------- confirmationMethod ----------------

    @Test
    void confirmationMethod_returns200WithMethod() {
        final User user = buildUser(1L);
        setAuthentication(user);
        when(accountDeletionService.confirmationMethod(user)).thenReturn(DeletionConfirmationMethod.PASSWORD);

        final ResponseEntity<Map<String, String>> response = controller.confirmationMethod();

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isEqualTo(Map.of("method", "PASSWORD"));
    }

    @Test
    void confirmationMethod_returns401_whenNoAuthentication() {
        final ResponseEntity<Map<String, String>> response = controller.confirmationMethod();

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        verify(accountDeletionService, never()).confirmationMethod(any());
    }

    @Test
    void confirmationMethod_returns401_whenAuthDetailsNotUser() {
        final UsernamePasswordAuthenticationToken auth =
                new UsernamePasswordAuthenticationToken("principal", "credentials");
        auth.setDetails("not-a-user-object");
        SecurityContextHolder.getContext().setAuthentication(auth);

        final ResponseEntity<Map<String, String>> response = controller.confirmationMethod();

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    // ---------------- requestOtp ----------------

    @Test
    void requestOtp_delegatesToService() {
        final User user = buildUser(1L);
        setAuthentication(user);
        when(cookieHelper.clientIp(any())).thenReturn(CLIENT_IP);

        controller.requestOtp(request);

        verify(accountDeletionService).requestOtp(eq(user), eq(CLIENT_IP), any());
    }

    @Test
    void requestOtp_throws401_whenNoAuthentication() {
        assertThatThrownBy(() -> controller.requestOtp(request))
            .isInstanceOf(ResponseStatusException.class)
            .extracting(e -> ((ResponseStatusException) e).getStatusCode())
            .isEqualTo(HttpStatus.UNAUTHORIZED);
        verify(accountDeletionService, never()).requestOtp(any(), any(), any());
    }

    // ---------------- deleteAccount ----------------

    @Test
    void deleteAccount_returns200WithEffectiveDate() {
        final User user = buildUser(1L);
        setAuthentication(user);
        final Instant effectiveAt = Instant.now().plusSeconds(2_592_000);
        final AccountDeletionRequestDto req = new AccountDeletionRequestDto("pw", null);
        when(cookieHelper.clientIp(any())).thenReturn(CLIENT_IP);
        when(accountDeletionService.requestDeletion(eq(user), eq(req), eq(CLIENT_IP), any()))
            .thenReturn(effectiveAt);

        final ResponseEntity<AccountDeletionResponseDto> response = controller.deleteAccount(req, request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isEqualTo(new AccountDeletionResponseDto(effectiveAt));
    }

    @Test
    void deleteAccount_returns401_whenNoAuthentication() {
        final ResponseEntity<AccountDeletionResponseDto> response =
            controller.deleteAccount(new AccountDeletionRequestDto("pw", null), request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        verify(accountDeletionService, never()).requestDeletion(any(), any(), any(), any());
    }

    @Test
    void deleteAccount_toleratesNullBody() {
        final User user = buildUser(1L);
        setAuthentication(user);
        when(cookieHelper.clientIp(any())).thenReturn(CLIENT_IP);
        when(accountDeletionService.requestDeletion(eq(user), eq(new AccountDeletionRequestDto(null, null)),
                any(), any())).thenReturn(Instant.now());

        final ResponseEntity<AccountDeletionResponseDto> response = controller.deleteAccount(null, request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    // ---------------- cancel ----------------

    @Test
    void cancel_delegatesToService_returns200() {
        when(cookieHelper.clientIp(any())).thenReturn(CLIENT_IP);

        final ResponseEntity<Map<String, String>> response =
            controller.cancel(new AccountDeletionCancelRequest("raw-token"), request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        verify(accountDeletionService).cancelDeletion(eq("raw-token"), eq(CLIENT_IP), any());
    }

    // ----------------------------------------------------------------
    // Helpers
    // ----------------------------------------------------------------

    private static User buildUser(final Long userId) {
        final User user = mock(User.class);
        when(user.getId()).thenReturn(userId);
        return user;
    }

    private static void setAuthentication(final User user) {
        final UsernamePasswordAuthenticationToken auth =
                new UsernamePasswordAuthenticationToken(user.getId(), null);
        auth.setDetails(user);
        SecurityContextHolder.getContext().setAuthentication(auth);
    }
}

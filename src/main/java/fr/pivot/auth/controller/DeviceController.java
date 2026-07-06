package fr.pivot.auth.controller;

import fr.pivot.auth.dto.TrustedDeviceDto;
import fr.pivot.auth.entity.User;
import fr.pivot.auth.service.TrustedDeviceService;
import fr.pivot.config.TokenAuthenticationFilter;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

/**
 * REST controller for the "trusted devices" self-service screen (US01.4.2).
 *
 * <p>A thin HTTP layer over {@link TrustedDeviceService} — all ownership checks, current-device
 * protection and mapping to {@link TrustedDeviceDto} are delegated there. {@code userId} is always
 * resolved from the {@link User} in the authenticated request's details (populated by
 * {@link fr.pivot.config.TokenAuthenticationFilter}), never from a path/query/body value.
 *
 * <p>The current session's {@link fr.pivot.auth.entity.AccessToken} id is not carried by the
 * {@link Authentication} populated by the filter (only the resolved {@link User} is). Rather than
 * re-validating the same bearer token a second time against the database, it is read from the
 * {@value TokenAuthenticationFilter#CURRENT_TOKEN_ID_ATTRIBUTE} request attribute that
 * {@link TokenAuthenticationFilter} already populates while authenticating the request. This
 * mirrors {@link SessionController} exactly (own {@code currentUser()} /
 * {@code resolveCurrentTokenId()} / {@code requireCurrentTokenId()} helpers — not shared via
 * inheritance, consistent with how this codebase structures these two controllers).
 */
@RestController
@RequestMapping("/api/auth/devices")
public class DeviceController {

    private static final Logger LOG = LoggerFactory.getLogger(DeviceController.class);

    private final TrustedDeviceService trustedDeviceService;

    /**
     * Constructs the controller with its required service collaborator.
     *
     * @param trustedDeviceService manages listing and revocation of trusted devices
     */
    public DeviceController(final TrustedDeviceService trustedDeviceService) {
        this.trustedDeviceService = trustedDeviceService;
    }

    /**
     * Lists the current user's trusted devices, most recently active first.
     *
     * @param http incoming request (used to resolve the current session's token id)
     * @return 200 with the list of {@link TrustedDeviceDto}
     */
    @GetMapping
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<List<TrustedDeviceDto>> listDevices(final HttpServletRequest http) {
        final User user = currentUser();
        final Long currentTokenId = resolveCurrentTokenId(http);
        LOG.info("event=LIST_TRUSTED_DEVICES userId={}", user.getId());
        return ResponseEntity.ok(trustedDeviceService.listDevices(user, currentTokenId));
    }

    /**
     * Revokes a single trusted device belonging to the current user.
     *
     * @param deviceId id of the {@link fr.pivot.auth.entity.TrustedDevice} to revoke
     * @param http     incoming request (used to resolve the current session's token id)
     * @return 204 No Content on success
     * @throws ResponseStatusException 404 if the device does not belong to the current user,
     *     403 if {@code deviceId} is the current device, 401 if the current session cannot
     *     be resolved
     */
    @DeleteMapping("/{deviceId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("isAuthenticated()")
    public void revokeDevice(@PathVariable final Long deviceId, final HttpServletRequest http) {
        final User user = currentUser();
        final Long currentTokenId = requireCurrentTokenId(http);
        trustedDeviceService.revokeDevice(user, deviceId, currentTokenId);
    }

    // ----------------------------------------------------------------
    // Private helpers
    // ----------------------------------------------------------------

    private User currentUser() {
        final Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getDetails() instanceof User user)) {
            LOG.warn("event=DEVICES_REJECTED reason=invalid_auth_details");
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        }
        return user;
    }

    /**
     * Resolves the {@link fr.pivot.auth.entity.AccessToken} id backing the current request from
     * the {@link TokenAuthenticationFilter#CURRENT_TOKEN_ID_ATTRIBUTE} request attribute, or
     * {@code null} if it cannot be resolved (never fails the request — used on the read path
     * where a missing {@code isCurrent} flag is preferable to a hard error).
     */
    private Long resolveCurrentTokenId(final HttpServletRequest http) {
        final Object attribute = http.getAttribute(TokenAuthenticationFilter.CURRENT_TOKEN_ID_ATTRIBUTE);
        return attribute instanceof Long tokenId ? tokenId : null;
    }

    /**
     * Same as {@link #resolveCurrentTokenId} but fails the request with 401 if the current
     * session cannot be resolved — required on write paths, where silently treating "unknown
     * current session" as "no current session" would let a caller revoke its own current device
     * without the 403 guard ever being evaluated.
     */
    private Long requireCurrentTokenId(final HttpServletRequest http) {
        final Long currentTokenId = resolveCurrentTokenId(http);
        if (currentTokenId == null) {
            LOG.warn("event=DEVICES_REJECTED reason=current_token_unresolved");
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        }
        return currentTokenId;
    }
}

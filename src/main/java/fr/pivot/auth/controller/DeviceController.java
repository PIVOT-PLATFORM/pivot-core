package fr.pivot.auth.controller;

import fr.pivot.auth.dto.TrustedDeviceDto;
import fr.pivot.auth.entity.User;
import fr.pivot.auth.service.TrustedDeviceService;
import fr.pivot.config.CurrentSessionResolver;
import fr.pivot.config.TokenAuthenticationFilter;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
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
 * {@link TokenAuthenticationFilter} already populates while authenticating the request — via the
 * shared {@link CurrentSessionResolver}, also used by {@link SessionController} (US02.2.3), which
 * needs the exact same "who is calling" / "which session" resolution (DRY — the resolution logic
 * used to be copy-pasted per controller; each controller still keeps its own
 * {@code ResponseStatusException} status codes and structured log event names, composition over
 * a shared base class, consistent with how this codebase structures these two controllers).
 */
@RestController
@RequestMapping("/api/auth/devices")
public class DeviceController {

    private static final Logger LOG = LoggerFactory.getLogger(DeviceController.class);

    /** Structured-log event name used by {@link CurrentSessionResolver} on rejection. */
    private static final String REJECTED_EVENT = "DEVICES_REJECTED";

    private final TrustedDeviceService trustedDeviceService;
    private final CurrentSessionResolver sessionResolver;

    /**
     * Constructs the controller with its required collaborators.
     *
     * @param trustedDeviceService manages listing and revocation of trusted devices
     * @param sessionResolver      resolves the authenticated user and current token id from a request
     */
    public DeviceController(final TrustedDeviceService trustedDeviceService,
                             final CurrentSessionResolver sessionResolver) {
        this.trustedDeviceService = trustedDeviceService;
        this.sessionResolver = sessionResolver;
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
        final User user = sessionResolver.currentUser(LOG, REJECTED_EVENT);
        final Long currentTokenId = sessionResolver.currentTokenId(http).orElse(null);
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
        final User user = sessionResolver.currentUser(LOG, REJECTED_EVENT);
        // Required (not resolveCurrentTokenId's nullable variant): silently treating "unknown
        // current session" as "no current session" would let a caller revoke its own current
        // device without the 403 current-device guard in TrustedDeviceService ever being
        // evaluated (see SessionController.revokeSession for the identical rationale on writes).
        final Long currentTokenId = sessionResolver.requireCurrentTokenId(http, LOG, REJECTED_EVENT);
        trustedDeviceService.revokeDevice(user, deviceId, currentTokenId);
    }
}

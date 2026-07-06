package fr.pivot.auth.service;

import fr.pivot.auth.dto.TrustedDeviceDto;
import fr.pivot.auth.entity.AccessToken;
import fr.pivot.auth.entity.TrustedDevice;
import fr.pivot.auth.entity.User;
import fr.pivot.auth.event.TrustedDeviceRevokedEvent;
import fr.pivot.auth.repository.AccessTokenRepository;
import fr.pivot.auth.repository.FeatureFlagRepository;
import fr.pivot.auth.repository.TrustedDeviceRepository;
import fr.pivot.auth.util.HtmlStripper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;

/**
 * Manages trusted-device records used to skip new-device OTP on subsequent logins.
 *
 * <p>A device is identified by a browser fingerprint scoped to a user. Records use a sliding
 * TTL ({@code DEVICE_TTL_DAYS} feature flag, default 90 days) renewed on each successful
 * trusted login.
 *
 * <p>Also backs the "trusted devices" self-service screen (US01.4.2):
 * {@link #listDevices(User, Long)} and {@link #revokeDevice(User, Long, Long)} — listing and
 * revocation are userId-scoped from the bearer token's resolved {@link User}, never from a
 * client-supplied identifier, same pattern as {@link SessionService#listSessions} and
 * {@link SessionService#revokeSession}.
 */
@Service
public class TrustedDeviceService {

    private static final Logger LOG = LoggerFactory.getLogger(TrustedDeviceService.class);

    private static final int DEVICE_TTL_DEFAULT_DAYS = 90;

    /**
     * Max length re-applied to {@code deviceName} when mapping to {@link TrustedDeviceDto} —
     * defence in depth alongside the sanitization already applied at trust time, in case any
     * row was ever written by another path. Same value used by
     * {@link SessionService#toSessionDto}.
     */
    private static final int DEVICE_NAME_MAX_LENGTH = 200;

    private final TrustedDeviceRepository repo;
    private final FeatureFlagRepository featureFlagRepo;
    private final AccessTokenRepository tokenRepo;
    private final ApplicationEventPublisher eventPublisher;

    /**
     * @param repo            JPA repository for trusted devices
     * @param featureFlagRepo admin-configurable settings (DEVICE_TTL_DAYS)
     * @param tokenRepo       direct read access to {@link AccessToken} rows, used to resolve the
     *                        device fingerprint backing the current request (US01.4.2)
     * @param eventPublisher  publishes {@link TrustedDeviceRevokedEvent} on revocation
     */
    public TrustedDeviceService(
            final TrustedDeviceRepository repo,
            final FeatureFlagRepository featureFlagRepo,
            final AccessTokenRepository tokenRepo,
            final ApplicationEventPublisher eventPublisher) {
        this.repo = repo;
        this.featureFlagRepo = featureFlagRepo;
        this.tokenRepo = tokenRepo;
        this.eventPublisher = eventPublisher;
    }

    /** Returns true if device is known and not expired. Renews sliding TTL. */
    @Transactional
    public boolean isTrusted(User user, String fingerprint) {
        Optional<TrustedDevice> opt = repo.findByUserIdAndDeviceFingerprint(user.getId(), fingerprint);
        if (opt.isEmpty()) {
            return false;
        }
        TrustedDevice td = opt.get();
        if (td.isExpired()) {
            repo.delete(td);
            return false;
        }
        final int ttlDays = featureFlagRepo.getInt("DEVICE_TTL_DAYS", DEVICE_TTL_DEFAULT_DAYS);
        td.setLastSeenAt(Instant.now());
        td.setExpiresAt(Instant.now().plus(ttlDays, ChronoUnit.DAYS));
        repo.save(td);
        return true;
    }

    /**
     * Trust a device — after OTP confirmation (US01.4.1), first-time OIDC, or a passive
     * suspicious-login alert with no active OTP gate (US01.4.3a, so the same device does not
     * re-alert on every subsequent login).
     *
     * @param user        the user the device belongs to
     * @param fingerprint browser fingerprint identifying the device
     * @param deviceName  human-readable device label (optional)
     * @param ip          client IP the device was trusted from — captured once, here, on both
     *                    the create and update-existing-record paths (never updated by
     *                    {@link #isTrusted(User, String)})
     */
    @Transactional
    public void trust(User user, String fingerprint, String deviceName, String ip) {
        final int ttlDays = featureFlagRepo.getInt("DEVICE_TTL_DAYS", DEVICE_TTL_DEFAULT_DAYS);
        TrustedDevice td = repo.findByUserIdAndDeviceFingerprint(user.getId(), fingerprint)
            .orElseGet(TrustedDevice::new);
        td.setUser(user);
        td.setDeviceFingerprint(fingerprint);
        td.setDeviceName(deviceName);
        td.setIpAddress(ip);
        td.setLastSeenAt(Instant.now());
        td.setExpiresAt(Instant.now().plus(ttlDays, ChronoUnit.DAYS));
        repo.save(td);
    }

    /**
     * Revokes trust for a specific device — used when the account owner confirms (via full
     * re-authentication) that a flagged login was not them (US01.4.3a "Not me" link). No-op if
     * the device is not (or no longer) trusted.
     *
     * @param user        the account owner
     * @param fingerprint the device fingerprint to untrust
     */
    @Transactional
    public void revoke(User user, String fingerprint) {
        repo.findByUserIdAndDeviceFingerprint(user.getId(), fingerprint).ifPresent(repo::delete);
    }

    /**
     * Lists the current user's trusted devices, most recently active first (US01.4.2).
     *
     * <p>{@code userId} is always taken from the authenticated {@link User} — never from a
     * client-supplied parameter — so this can never return another user's devices.
     *
     * @param user           the authenticated user (resolved from the bearer token)
     * @param currentTokenId id of the {@link AccessToken} backing the current request, or
     *                       {@code null} if it could not be resolved (no device is then
     *                       flagged {@code isCurrent})
     * @return trusted devices mapped to {@link TrustedDeviceDto}, most recently active first
     */
    @Transactional(readOnly = true)
    public List<TrustedDeviceDto> listDevices(final User user, final Long currentTokenId) {
        final String currentFingerprint = resolveCurrentFingerprint(currentTokenId);
        return repo.findByUserIdOrderByLastSeenAtDesc(user.getId())
            .stream()
            .map(device -> toTrustedDeviceDto(device, currentFingerprint))
            .toList();
    }

    /**
     * Revokes a single trusted device belonging to the current user (US01.4.2).
     *
     * <p>Ownership is checked first: a {@code deviceId} that does not belong to {@code user}
     * (whether it does not exist or belongs to another user) yields 404 — the response never
     * reveals whether the device exists for someone else. Only once ownership is established is
     * the current-device guard applied — revoking the device backing the request that asked for
     * the revocation is rejected with 403 (API-level protection, independent of the UI).
     *
     * <p>On success, publishes a {@link TrustedDeviceRevokedEvent} after the device row is
     * deleted — see that event's JavaDoc for the US01.5.1 integration point.
     *
     * @param user           the authenticated user (resolved from the bearer token)
     * @param deviceId       id of the {@link TrustedDevice} to revoke (path variable, untrusted)
     * @param currentTokenId id of the session backing the current request
     * @throws ResponseStatusException 404 if the device does not exist or belongs to another
     *     user; 403 if {@code deviceId} is the current device
     */
    @Transactional
    public void revokeDevice(final User user, final Long deviceId, final Long currentTokenId) {
        final TrustedDevice device = repo.findByIdAndUserId(deviceId, user.getId())
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));

        final String currentFingerprint = resolveCurrentFingerprint(currentTokenId);
        if (device.getDeviceFingerprint().equals(currentFingerprint)) {
            LOG.warn("event=DEVICE_REVOKE_REJECTED reason=is_current_device userId={} deviceId={}",
                user.getId(), deviceId);
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Impossible de révoquer l'appareil courant");
        }

        final String deviceName = device.getDeviceName();
        repo.delete(device);
        LOG.info("event=DEVICE_REVOKED userId={} deviceId={}", user.getId(), deviceId);
        eventPublisher.publishEvent(new TrustedDeviceRevokedEvent(
            user.getId(), user.getEmail(), user.getFirstName(), user.getLocale(), deviceName, Instant.now()));
    }

    // ----------------------------------------------------------------
    // Private helpers
    // ----------------------------------------------------------------

    private TrustedDeviceDto toTrustedDeviceDto(final TrustedDevice device, final String currentFingerprint) {
        return new TrustedDeviceDto(
            device.getId(),
            HtmlStripper.stripAndTruncate(device.getDeviceName(), DEVICE_NAME_MAX_LENGTH),
            device.getIpAddress(),
            device.getConfirmedAt(),
            device.getLastSeenAt(),
            device.getDeviceFingerprint().equals(currentFingerprint));
    }

    /**
     * Resolves the device fingerprint of the {@link AccessToken} backing the current request, so
     * it can be compared against each {@link TrustedDevice#getDeviceFingerprint()} to determine
     * "is this the current device". There is no per-request device-fingerprint header read
     * anywhere in this codebase — {@code currentTokenId} (resolved by controllers from
     * {@link fr.pivot.config.TokenAuthenticationFilter#CURRENT_TOKEN_ID_ATTRIBUTE}) is the only
     * existing mechanism.
     *
     * @param currentTokenId id of the {@link AccessToken} backing the current request, or
     *                       {@code null} if it could not be resolved
     * @return the current device's fingerprint, or {@code null} if {@code currentTokenId} is
     *     {@code null} or does not resolve to an existing token — in both cases no device will
     *     ever compare equal, so nothing is flagged as current
     */
    private String resolveCurrentFingerprint(final Long currentTokenId) {
        if (currentTokenId == null) {
            return null;
        }
        return tokenRepo.findById(currentTokenId)
            .map(AccessToken::getDeviceFingerprint)
            .orElse(null);
    }
}

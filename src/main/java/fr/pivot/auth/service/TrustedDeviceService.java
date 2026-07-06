package fr.pivot.auth.service;

import fr.pivot.auth.entity.TrustedDevice;
import fr.pivot.auth.entity.User;
import fr.pivot.auth.repository.FeatureFlagRepository;
import fr.pivot.auth.repository.TrustedDeviceRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;

/**
 * Manages trusted-device records used to skip new-device OTP on subsequent logins.
 *
 * <p>A device is identified by a browser fingerprint scoped to a user. Records use a sliding
 * TTL ({@code DEVICE_TTL_DAYS} feature flag, default 90 days) renewed on each successful
 * trusted login.
 */
@Service
public class TrustedDeviceService {

    private static final int DEVICE_TTL_DEFAULT_DAYS = 90;

    private final TrustedDeviceRepository repo;
    private final FeatureFlagRepository featureFlagRepo;

    /**
     * @param repo            JPA repository for trusted devices
     * @param featureFlagRepo admin-configurable settings (DEVICE_TTL_DAYS)
     */
    public TrustedDeviceService(TrustedDeviceRepository repo, FeatureFlagRepository featureFlagRepo) {
        this.repo = repo;
        this.featureFlagRepo = featureFlagRepo;
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
     */
    @Transactional
    public void trust(User user, String fingerprint, String deviceName) {
        final int ttlDays = featureFlagRepo.getInt("DEVICE_TTL_DAYS", DEVICE_TTL_DEFAULT_DAYS);
        TrustedDevice td = repo.findByUserIdAndDeviceFingerprint(user.getId(), fingerprint)
            .orElseGet(TrustedDevice::new);
        td.setUser(user);
        td.setDeviceFingerprint(fingerprint);
        td.setDeviceName(deviceName);
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
}

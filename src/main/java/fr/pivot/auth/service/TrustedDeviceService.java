package fr.pivot.auth.service;

import fr.pivot.auth.entity.TrustedDevice;
import fr.pivot.auth.entity.User;
import fr.pivot.auth.repository.TrustedDeviceRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;

/**
 * Manages trusted-device records used to skip new-device OTP on subsequent logins.
 *
 * <p>A device is identified by a browser fingerprint scoped to a user. Records use a sliding
 * TTL ({@code pivot.auth.device-ttl-days}) renewed on each successful trusted login.
 */
@Service
public class TrustedDeviceService {

    private final TrustedDeviceRepository repo;
    private final int deviceTtlDays;

    /**
     * @param repo          JPA repository for trusted devices
     * @param deviceTtlDays sliding TTL in days before a trusted device must re-verify
     */
    public TrustedDeviceService(
            TrustedDeviceRepository repo,
            @Value("${pivot.auth.device-ttl-days:90}") int deviceTtlDays) {
        this.repo = repo;
        this.deviceTtlDays = deviceTtlDays;
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
        // Sliding window renewal
        td.setLastSeenAt(Instant.now());
        td.setExpiresAt(Instant.now().plus(deviceTtlDays, ChronoUnit.DAYS));
        repo.save(td);
        return true;
    }

    /** Trust a device (after OTP confirmation or first-time OIDC). */
    @Transactional
    public void trust(User user, String fingerprint, String deviceName) {
        TrustedDevice td = repo.findByUserIdAndDeviceFingerprint(user.getId(), fingerprint)
            .orElseGet(TrustedDevice::new);
        td.setUser(user);
        td.setDeviceFingerprint(fingerprint);
        td.setDeviceName(deviceName);
        td.setLastSeenAt(Instant.now());
        td.setExpiresAt(Instant.now().plus(deviceTtlDays, ChronoUnit.DAYS));
        repo.save(td);
    }
}

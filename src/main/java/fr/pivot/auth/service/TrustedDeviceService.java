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

@Service
public class TrustedDeviceService {

    private final TrustedDeviceRepository repo;
    private final int deviceTtlDays;

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

    /** True if the user has no trusted devices (first-ever login). */
    public boolean hasNoTrustedDevices(User user) {
        return !repo.findByUserIdAndDeviceFingerprint(user.getId(), "").isPresent()
            && repo.findAll().stream().noneMatch(d -> d.getUser().getId().equals(user.getId()));
    }
}

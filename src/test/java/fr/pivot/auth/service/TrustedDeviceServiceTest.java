package fr.pivot.auth.service;

import fr.pivot.auth.entity.TrustedDevice;
import fr.pivot.auth.entity.User;
import fr.pivot.auth.repository.FeatureFlagRepository;
import fr.pivot.auth.repository.TrustedDeviceRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link TrustedDeviceService} — trust lookup with sliding TTL renewal,
 * expiry eviction and device trust persistence.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class TrustedDeviceServiceTest {

    @Mock private TrustedDeviceRepository repo;
    @Mock private FeatureFlagRepository featureFlagRepo;
    @Mock private User user;

    private TrustedDeviceService service;

    @BeforeEach
    void setUp() {
        when(featureFlagRepo.getInt("DEVICE_TTL_DAYS", 90)).thenReturn(90);
        service = new TrustedDeviceService(repo, featureFlagRepo);
        when(user.getId()).thenReturn(7L);
    }

    @Test
    void isTrusted_returnsFalse_whenDeviceUnknown() {
        when(repo.findByUserIdAndDeviceFingerprint(7L, "fp")).thenReturn(Optional.empty());

        assertThat(service.isTrusted(user, "fp")).isFalse();
    }

    @Test
    void isTrusted_deletesAndReturnsFalse_whenExpired() {
        final TrustedDevice td = new TrustedDevice();
        td.setExpiresAt(Instant.now().minusSeconds(10));
        when(repo.findByUserIdAndDeviceFingerprint(7L, "fp")).thenReturn(Optional.of(td));

        assertThat(service.isTrusted(user, "fp")).isFalse();
        verify(repo).delete(td);
    }

    @Test
    void isTrusted_renewsAndReturnsTrue_whenValid() {
        final TrustedDevice td = new TrustedDevice();
        td.setExpiresAt(Instant.now().plusSeconds(3600));
        when(repo.findByUserIdAndDeviceFingerprint(7L, "fp")).thenReturn(Optional.of(td));

        assertThat(service.isTrusted(user, "fp")).isTrue();
        assertThat(td.getLastSeenAt()).isNotNull();
        verify(repo).save(td);
    }

    @Test
    void trust_createsNewRecord_whenNoneExists() {
        when(repo.findByUserIdAndDeviceFingerprint(7L, "fp")).thenReturn(Optional.empty());

        service.trust(user, "fp", "Chrome");

        verify(repo).save(any(TrustedDevice.class));
    }

    @Test
    void trust_updatesExistingRecord() {
        final TrustedDevice td = new TrustedDevice();
        when(repo.findByUserIdAndDeviceFingerprint(7L, "fp")).thenReturn(Optional.of(td));

        service.trust(user, "fp", "Firefox");

        assertThat(td.getDeviceName()).isEqualTo("Firefox");
        assertThat(td.getExpiresAt()).isNotNull();
        verify(repo).save(td);
    }

    @Test
    void isTrusted_doesNotSave_whenDeviceUnknown() {
        when(repo.findByUserIdAndDeviceFingerprint(7L, "fp")).thenReturn(Optional.empty());

        service.isTrusted(user, "fp");

        verify(repo, never()).save(any());
    }
}

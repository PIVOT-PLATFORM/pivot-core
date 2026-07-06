package fr.pivot.auth.service;

import fr.pivot.auth.dto.TrustedDeviceDto;
import fr.pivot.auth.entity.AccessToken;
import fr.pivot.auth.entity.TrustedDevice;
import fr.pivot.auth.entity.User;
import fr.pivot.auth.event.TrustedDeviceRevokedEvent;
import fr.pivot.auth.repository.AccessTokenRepository;
import fr.pivot.auth.repository.FeatureFlagRepository;
import fr.pivot.auth.repository.TrustedDeviceRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link TrustedDeviceService} — trust lookup with sliding TTL renewal,
 * expiry eviction, device trust persistence, the "Not me" revoke confirmation (US01.4.3a:
 * {@link TrustedDeviceService#revoke}), and the "trusted devices" self-service screen
 * (US01.4.2: {@link TrustedDeviceService#listDevices} / {@link TrustedDeviceService#revokeDevice}).
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class TrustedDeviceServiceTest {

    @Mock private TrustedDeviceRepository repo;
    @Mock private FeatureFlagRepository featureFlagRepo;
    @Mock private AccessTokenRepository tokenRepo;
    @Mock private ApplicationEventPublisher eventPublisher;
    @Mock private User user;

    private TrustedDeviceService service;

    @BeforeEach
    void setUp() {
        when(featureFlagRepo.getInt("DEVICE_TTL_DAYS", 90)).thenReturn(90);
        service = new TrustedDeviceService(repo, featureFlagRepo, tokenRepo, eventPublisher);
        when(user.getId()).thenReturn(7L);
        when(user.getEmail()).thenReturn("alice@pivot.test");
        when(user.getFirstName()).thenReturn("Alice");
        when(user.getLocale()).thenReturn("fr");
    }

    // ---------------- isTrusted ----------------

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
    void isTrusted_doesNotSave_whenDeviceUnknown() {
        when(repo.findByUserIdAndDeviceFingerprint(7L, "fp")).thenReturn(Optional.empty());

        service.isTrusted(user, "fp");

        verify(repo, never()).save(any());
    }

    // ---------------- trust ----------------

    @Test
    void trust_createsNewRecord_whenNoneExists() {
        when(repo.findByUserIdAndDeviceFingerprint(7L, "fp")).thenReturn(Optional.empty());

        service.trust(user, "fp", "Chrome", "203.0.113.5");

        final ArgumentCaptor<TrustedDevice> captor = ArgumentCaptor.forClass(TrustedDevice.class);
        verify(repo).save(captor.capture());
        assertThat(captor.getValue().getIpAddress()).isEqualTo("203.0.113.5");
    }

    @Test
    void trust_updatesExistingRecord() {
        final TrustedDevice td = new TrustedDevice();
        when(repo.findByUserIdAndDeviceFingerprint(7L, "fp")).thenReturn(Optional.of(td));

        service.trust(user, "fp", "Firefox", "198.51.100.9");

        assertThat(td.getDeviceName()).isEqualTo("Firefox");
        assertThat(td.getIpAddress()).isEqualTo("198.51.100.9");
        assertThat(td.getExpiresAt()).isNotNull();
        verify(repo).save(td);
    }

    // ---------------- revoke (US01.4.3a "Not me" confirmation) ----------------

    @Test
    void revoke_deletesRecord_whenPresent() {
        final TrustedDevice td = new TrustedDevice();
        when(repo.findByUserIdAndDeviceFingerprint(7L, "fp")).thenReturn(Optional.of(td));

        service.revoke(user, "fp");

        verify(repo).delete(td);
    }

    @Test
    void revoke_isNoOp_whenAbsent() {
        when(repo.findByUserIdAndDeviceFingerprint(7L, "fp")).thenReturn(Optional.empty());

        service.revoke(user, "fp");

        verify(repo, never()).delete(any());
    }

    // ---------------- listDevices ----------------

    @Test
    void listDevices_mapsDevicesCorrectly_orderedByLastSeenDesc() {
        final TrustedDevice recent = deviceWithId(1L, "fp-recent", "Chrome", "203.0.113.1",
            Instant.parse("2026-01-01T00:00:00Z"), Instant.parse("2026-06-01T00:00:00Z"));
        final TrustedDevice older = deviceWithId(2L, "fp-older", "Firefox", "203.0.113.2",
            Instant.parse("2025-01-01T00:00:00Z"), Instant.parse("2026-01-01T00:00:00Z"));
        // Repo mock returns them already ordered — the service must preserve that order.
        when(repo.findByUserIdOrderByLastSeenAtDesc(7L)).thenReturn(List.of(recent, older));

        final List<TrustedDeviceDto> result = service.listDevices(user, null);

        assertThat(result).hasSize(2);
        assertThat(result.get(0).id()).isEqualTo(1L);
        assertThat(result.get(0).device()).isEqualTo("Chrome");
        assertThat(result.get(0).ip()).isEqualTo("203.0.113.1");
        assertThat(result.get(0).createdAt()).isEqualTo(Instant.parse("2026-01-01T00:00:00Z"));
        assertThat(result.get(0).lastSeenAt()).isEqualTo(Instant.parse("2026-06-01T00:00:00Z"));
        assertThat(result.get(1).id()).isEqualTo(2L);
    }

    @Test
    void listDevices_flagsIsCurrent_forDeviceMatchingCurrentFingerprint() {
        final TrustedDevice current = deviceWithId(1L, "fp-current", "Chrome", "203.0.113.1",
            Instant.now(), Instant.now());
        final TrustedDevice other = deviceWithId(2L, "fp-other", "Firefox", "203.0.113.2",
            Instant.now(), Instant.now());
        when(repo.findByUserIdOrderByLastSeenAtDesc(7L)).thenReturn(List.of(current, other));

        final AccessToken currentToken = mockTokenWithFingerprint("fp-current");
        when(tokenRepo.findById(99L)).thenReturn(Optional.of(currentToken));

        final List<TrustedDeviceDto> result = service.listDevices(user, 99L);

        assertThat(result).filteredOn(d -> d.id().equals(1L)).extracting(TrustedDeviceDto::isCurrent)
            .containsExactly(true);
        assertThat(result).filteredOn(d -> d.id().equals(2L)).extracting(TrustedDeviceDto::isCurrent)
            .containsExactly(false);
    }

    @Test
    void listDevices_noDeviceFlaggedCurrent_whenCurrentTokenIdIsNull() {
        final TrustedDevice device = deviceWithId(1L, "fp-1", "Chrome", "203.0.113.1",
            Instant.now(), Instant.now());
        when(repo.findByUserIdOrderByLastSeenAtDesc(7L)).thenReturn(List.of(device));

        final List<TrustedDeviceDto> result = service.listDevices(user, null);

        assertThat(result).extracting(TrustedDeviceDto::isCurrent).containsExactly(false);
    }

    @Test
    void listDevices_stripsHtmlFromDeviceName() {
        final TrustedDevice device = deviceWithId(1L, "fp-1", "<img src=x onerror=alert(1)>Chrome",
            "203.0.113.1", Instant.now(), Instant.now());
        when(repo.findByUserIdOrderByLastSeenAtDesc(7L)).thenReturn(List.of(device));

        final List<TrustedDeviceDto> result = service.listDevices(user, null);

        assertThat(result.get(0).device()).isEqualTo("Chrome");
    }

    // ---------------- revokeDevice ----------------

    @Test
    void revokeDevice_deletesAndPublishesEvent_onHappyPath() {
        final TrustedDevice device = deviceWithId(1L, "fp-other", "Safari", "203.0.113.3",
            Instant.now(), Instant.now());
        when(repo.findByIdAndUserId(1L, 7L)).thenReturn(Optional.of(device));
        final AccessToken currentToken = mockTokenWithFingerprint("fp-current");
        when(tokenRepo.findById(99L)).thenReturn(Optional.of(currentToken));

        service.revokeDevice(user, 1L, 99L);

        verify(repo).delete(device);
        final ArgumentCaptor<TrustedDeviceRevokedEvent> captor =
            ArgumentCaptor.forClass(TrustedDeviceRevokedEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());
        assertThat(captor.getValue().userId()).isEqualTo(7L);
        assertThat(captor.getValue().userEmail()).isEqualTo("alice@pivot.test");
        assertThat(captor.getValue().userFirstName()).isEqualTo("Alice");
        assertThat(captor.getValue().userLocale()).isEqualTo("fr");
        assertThat(captor.getValue().deviceName()).isEqualTo("Safari");
        assertThat(captor.getValue().occurredAt()).isNotNull();
    }

    @Test
    void revokeDevice_throws404_whenDeviceBelongsToAnotherUser_andDoesNotDeleteOrPublish() {
        when(repo.findByIdAndUserId(1L, 7L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.revokeDevice(user, 1L, 99L))
            .isInstanceOf(ResponseStatusException.class)
            .extracting(e -> ((ResponseStatusException) e).getStatusCode())
            .isEqualTo(HttpStatus.NOT_FOUND);

        verify(repo, never()).delete(any());
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    void revokeDevice_throws403_whenDeviceIsCurrentDevice_andDoesNotDeleteOrPublish() {
        final TrustedDevice device = deviceWithId(1L, "fp-current", "Chrome", "203.0.113.1",
            Instant.now(), Instant.now());
        when(repo.findByIdAndUserId(1L, 7L)).thenReturn(Optional.of(device));
        final AccessToken currentToken = mockTokenWithFingerprint("fp-current");
        when(tokenRepo.findById(99L)).thenReturn(Optional.of(currentToken));

        assertThatThrownBy(() -> service.revokeDevice(user, 1L, 99L))
            .isInstanceOf(ResponseStatusException.class)
            .extracting(e -> ((ResponseStatusException) e).getStatusCode())
            .isEqualTo(HttpStatus.FORBIDDEN);

        verify(repo, never()).delete(any());
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    void revokeDevice_proceeds_whenCurrentTokenIdIsNull() {
        // Safe-default analysis (see TrustedDeviceService#resolveCurrentFingerprint JavaDoc):
        // currentTokenId == null only reaches this service method when called directly (as here)
        // or when AccessToken resolution races a concurrent revoke — DeviceController's DELETE
        // path always resolves a non-null currentTokenId first (401 otherwise), so this can never
        // happen through the real write path. When it does happen, "nothing is ever current" is
        // the safe default here because a TrustedDevice is not the session/AccessToken itself:
        // deleting the trust record for what would have been "the current device" only means the
        // browser will need device-OTP again on its next login — no session is revoked, no
        // cross-user or privilege-escalation impact. Confirmed non-null-hostile: device fingerprint
        // is never null on a persisted row (NOT NULL column), so no NPE risk either.
        final TrustedDevice device = deviceWithId(1L, "fp-would-be-current", "Chrome",
            "203.0.113.1", Instant.now(), Instant.now());
        when(repo.findByIdAndUserId(1L, 7L)).thenReturn(Optional.of(device));

        service.revokeDevice(user, 1L, null);

        verify(repo).delete(device);
        verify(eventPublisher).publishEvent(any(TrustedDeviceRevokedEvent.class));
        verify(tokenRepo, never()).findById(any());
    }

    // ---------------- fixtures ----------------

    private TrustedDevice deviceWithId(final Long id, final String fingerprint, final String deviceName,
                                        final String ip, final Instant confirmedAt, final Instant lastSeenAt) {
        final TrustedDevice device = Mockito.mock(TrustedDevice.class);
        Mockito.lenient().when(device.getId()).thenReturn(id);
        Mockito.lenient().when(device.getDeviceFingerprint()).thenReturn(fingerprint);
        Mockito.lenient().when(device.getDeviceName()).thenReturn(deviceName);
        Mockito.lenient().when(device.getIpAddress()).thenReturn(ip);
        Mockito.lenient().when(device.getConfirmedAt()).thenReturn(confirmedAt);
        Mockito.lenient().when(device.getLastSeenAt()).thenReturn(lastSeenAt);
        return device;
    }

    private AccessToken mockTokenWithFingerprint(final String fingerprint) {
        final AccessToken token = Mockito.mock(AccessToken.class);
        when(token.getDeviceFingerprint()).thenReturn(fingerprint);
        return token;
    }
}

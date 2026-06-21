package fr.pivot.auth.entity;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/** Unit tests for {@link DeviceVerifyToken} accessors and state helpers. */
class DeviceVerifyTokenTest {

    @Test
    void settersAndGetters_roundTrip() {
        final DeviceVerifyToken dvt = new DeviceVerifyToken();
        final User user = new User();
        final Instant exp = Instant.now().plusSeconds(900);
        final Instant conf = Instant.now();

        dvt.setUser(user);
        dvt.setDeviceFingerprint("fp");
        dvt.setDeviceName("Chrome");
        dvt.setOtpHash("hash");
        dvt.setAttempts(2);
        dvt.setExpiresAt(exp);
        dvt.setConfirmedAt(conf);

        assertThat(dvt.getUser()).isSameAs(user);
        assertThat(dvt.getDeviceFingerprint()).isEqualTo("fp");
        assertThat(dvt.getDeviceName()).isEqualTo("Chrome");
        assertThat(dvt.getOtpHash()).isEqualTo("hash");
        assertThat(dvt.getAttempts()).isEqualTo(2);
        assertThat(dvt.getExpiresAt()).isEqualTo(exp);
        assertThat(dvt.getConfirmedAt()).isEqualTo(conf);
        assertThat(dvt.getCreatedAt()).isNotNull();
    }

    @Test
    void isExpired_reflectsExpiry() {
        final DeviceVerifyToken dvt = new DeviceVerifyToken();
        dvt.setExpiresAt(Instant.now().minusSeconds(1));
        assertThat(dvt.isExpired()).isTrue();

        dvt.setExpiresAt(Instant.now().plusSeconds(60));
        assertThat(dvt.isExpired()).isFalse();
    }

    @Test
    void isConfirmed_reflectsConfirmation() {
        final DeviceVerifyToken dvt = new DeviceVerifyToken();
        assertThat(dvt.isConfirmed()).isFalse();

        dvt.setConfirmedAt(Instant.now());
        assertThat(dvt.isConfirmed()).isTrue();
    }
}

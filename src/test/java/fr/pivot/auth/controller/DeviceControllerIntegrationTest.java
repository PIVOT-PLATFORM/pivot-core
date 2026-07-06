package fr.pivot.auth.controller;

import fr.pivot.AbstractIntegrationTest;
import fr.pivot.auth.entity.AuthMethod;
import fr.pivot.auth.entity.TrustedDevice;
import fr.pivot.auth.entity.User;
import fr.pivot.auth.repository.AccessTokenRepository;
import fr.pivot.auth.repository.TrustedDeviceRepository;
import fr.pivot.auth.repository.UserRepository;
import fr.pivot.auth.service.TokenService;
import fr.pivot.auth.service.TrustedDeviceService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration tests for {@code GET/DELETE /api/auth/devices} (US01.4.2).
 *
 * <p>Full Spring context + real PostgreSQL (Testcontainers) + the real Spring Security filter
 * chain (via {@code springSecurity()}) — trusted devices are created through
 * {@link TrustedDeviceService#trust} exactly as at device-OTP confirmation (US01.4.1), and the
 * "current session" is simulated by issuing a bearer token via {@link TokenService#issue} with a
 * device fingerprint matching one of the trusted devices — this exercises the full path the
 * frontend will use, including the ownership (cross-user → 404) and current-device (→ 403) guards.
 *
 * <p>Traceability (US01.4.2 AC):
 * <ul>
 *   <li>"GET /api/auth/devices liste les appareils de confiance (nom, IP, date)" —
 *       {@code list_returnsOwnDevices_withNameIpDateAndCurrentFlag}</li>
 *   <li>"DELETE /api/auth/devices/{deviceId} révoque un appareil" —
 *       {@code deleteOne_revokesOwnNonCurrentDevice_returns204_andRowDeleted}</li>
 *   <li>"DELETE sur l'appareil courant retourne 403" —
 *       {@code deleteOne_currentDevice_returns403_andDoesNotDelete}</li>
 *   <li>IDOR — appartenance vérifiée avant tout traitement, jamais 403 cross-user —
 *       {@code deleteOne_crossUser_returns404_andDoesNotDelete}</li>
 * </ul>
 */
class DeviceControllerIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private WebApplicationContext webApplicationContext;

    @Autowired
    private TokenService tokenService;

    @Autowired
    private TrustedDeviceService trustedDeviceService;

    @Autowired
    private AccessTokenRepository tokenRepo;

    @Autowired
    private TrustedDeviceRepository trustedDeviceRepo;

    @Autowired
    private UserRepository userRepo;

    private MockMvc mockMvc;
    private User userAlice;
    private User userAdmin;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext)
            .apply(springSecurity())
            .build();
        // Seeded by V2__test_seeds.sql (Flyway, profile test, tenant_id=1)
        userAlice = userRepo.findByTenantIdAndEmailAndDeletedAtIsNull(1L, "user@pivot.test")
            .orElseThrow(() -> new IllegalStateException("Test user 'user@pivot.test' not found"));
        userAdmin = userRepo.findByTenantIdAndEmailAndDeletedAtIsNull(1L, "admin@pivot.test")
            .orElseThrow(() -> new IllegalStateException("Test user 'admin@pivot.test' not found"));
        cleanupDevicesAndTokens();
    }

    @AfterEach
    void tearDown() {
        cleanupDevicesAndTokens();
        SecurityContextHolder.clearContext();
    }

    private void cleanupDevicesAndTokens() {
        tokenRepo.deleteByUserId(userAlice.getId());
        tokenRepo.deleteByUserId(userAdmin.getId());
        trustedDeviceRepo.deleteAll(trustedDeviceRepo.findByUserIdOrderByLastSeenAtDesc(userAlice.getId()));
        trustedDeviceRepo.deleteAll(trustedDeviceRepo.findByUserIdOrderByLastSeenAtDesc(userAdmin.getId()));
    }

    /** Trusts a device for {@code user} and returns its persisted {@link TrustedDevice} id. */
    private Long trustDevice(final User user, final String fingerprint, final String deviceName, final String ip) {
        trustedDeviceService.trust(user, fingerprint, deviceName, ip);
        return trustedDeviceRepo.findByUserIdAndDeviceFingerprint(user.getId(), fingerprint)
            .orElseThrow().getId();
    }

    /** Issues a bearer token for {@code user} whose device fingerprint matches a trusted device. */
    private String issueToken(final User user, final String fingerprint, final String deviceName, final String ip) {
        return tokenService.issue(user, fingerprint, deviceName, "Mozilla/5.0 (test)", ip, AuthMethod.PASSWORD, false)
            .rawToken();
    }

    // ----------------------------------------------------------------
    // GET /api/auth/devices
    // ----------------------------------------------------------------

    @Test
    void list_returnsOwnDevices_withNameIpDateAndCurrentFlag() throws Exception {
        trustDevice(userAlice, "fp-current", "Chrome sur Windows", "203.0.113.1");
        trustDevice(userAlice, "fp-other", "Safari sur iPhone", "203.0.113.2");
        trustDevice(userAdmin, "fp-admin", "Firefox sur Linux", "198.51.100.1"); // must never appear

        final String currentRaw = issueToken(userAlice, "fp-current", "Chrome sur Windows", "203.0.113.1");

        mockMvc.perform(get("/api/auth/devices")
                .header("Authorization", "Bearer " + currentRaw))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.length()").value(2))
            .andExpect(jsonPath("$[*].device").value(org.hamcrest.Matchers.containsInAnyOrder(
                "Chrome sur Windows", "Safari sur iPhone")))
            .andExpect(jsonPath("$[*].ip").value(org.hamcrest.Matchers.containsInAnyOrder(
                "203.0.113.1", "203.0.113.2")))
            .andExpect(jsonPath("$[*].isCurrent").value(org.hamcrest.Matchers.containsInAnyOrder(true, false)))
            .andExpect(jsonPath("$[*].createdAt").exists())
            .andExpect(jsonPath("$[*].device").value(
                org.hamcrest.Matchers.not(org.hamcrest.Matchers.hasItem("Firefox sur Linux"))));
    }

    @Test
    void list_ordersByLastSeenDesc() throws Exception {
        trustDevice(userAlice, "fp-a", "Device A", "203.0.113.10");
        // lastSeenAt has no public setter (set internally to Instant.now() by trust()) — a short
        // real delay guarantees a distinct, strictly later Instant for "Device B", avoiding
        // flakiness in the DESC-ordering assertion below (same pattern as
        // SuperAdminTenantIntegrationTest#ac_defaultSort_ordersByCreatedAtDescending).
        Thread.sleep(5);
        trustDevice(userAlice, "fp-b", "Device B", "203.0.113.11");
        final String currentRaw = issueToken(userAlice, "fp-b", "Device B", "203.0.113.11");

        mockMvc.perform(get("/api/auth/devices")
                .header("Authorization", "Bearer " + currentRaw))
            .andExpect(status().isOk())
            // Device B was trusted after Device A, so it has the more recent lastSeenAt.
            .andExpect(jsonPath("$[0].device").value("Device B"))
            .andExpect(jsonPath("$[1].device").value("Device A"));
    }

    @Test
    void list_stripsHtmlFromDeviceName_inResponse() throws Exception {
        trustDevice(userAlice, "fp-xss", "<img src=x onerror=alert(1)>Chrome", "203.0.113.1");
        final String raw = issueToken(userAlice, "fp-xss", "<img src=x onerror=alert(1)>Chrome", "203.0.113.1");

        mockMvc.perform(get("/api/auth/devices")
                .header("Authorization", "Bearer " + raw))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].device").value("Chrome"))
            .andExpect(jsonPath("$[0].device", org.hamcrest.Matchers.not(
                org.hamcrest.Matchers.containsString("<"))));
    }

    @Test
    void list_returns403_whenNoBearerToken() throws Exception {
        // No AuthenticationEntryPoint is configured (see SecurityConfig) — Spring Security's
        // default for an unauthenticated request denied by isAuthenticated() is 403, not 401,
        // consistent with every other authenticated endpoint (e.g. SessionController).
        mockMvc.perform(get("/api/auth/devices"))
            .andExpect(status().isForbidden());
    }

    // ----------------------------------------------------------------
    // DELETE /api/auth/devices/{deviceId}
    // ----------------------------------------------------------------

    @Test
    void deleteOne_revokesOwnNonCurrentDevice_returns204_andRowDeleted() throws Exception {
        final Long otherId = trustDevice(userAlice, "fp-other", "Safari", "203.0.113.2");
        trustDevice(userAlice, "fp-current", "Chrome", "203.0.113.1");
        final String currentRaw = issueToken(userAlice, "fp-current", "Chrome", "203.0.113.1");

        mockMvc.perform(delete("/api/auth/devices/{deviceId}", otherId)
                .header("Authorization", "Bearer " + currentRaw))
            .andExpect(status().isNoContent());

        assertThat(trustedDeviceRepo.findById(otherId)).isEmpty();
    }

    @Test
    void deleteOne_crossUser_returns404_andDoesNotDelete() throws Exception {
        trustDevice(userAlice, "fp-current", "Chrome", "203.0.113.1");
        final String currentRaw = issueToken(userAlice, "fp-current", "Chrome", "203.0.113.1");
        final Long adminDeviceId = trustDevice(userAdmin, "fp-admin", "Firefox", "198.51.100.1");

        mockMvc.perform(delete("/api/auth/devices/{deviceId}", adminDeviceId)
                .header("Authorization", "Bearer " + currentRaw))
            .andExpect(status().isNotFound());

        assertThat(trustedDeviceRepo.findById(adminDeviceId)).isPresent();
    }

    @Test
    void deleteOne_currentDevice_returns403_andDoesNotDelete() throws Exception {
        final Long currentId = trustDevice(userAlice, "fp-current", "Chrome", "203.0.113.1");
        final String currentRaw = issueToken(userAlice, "fp-current", "Chrome", "203.0.113.1");

        mockMvc.perform(delete("/api/auth/devices/{deviceId}", currentId)
                .header("Authorization", "Bearer " + currentRaw))
            .andExpect(status().isForbidden());

        assertThat(trustedDeviceRepo.findById(currentId)).isPresent();
    }

    @Test
    void deleteOne_unknownId_returns404() throws Exception {
        trustDevice(userAlice, "fp-current", "Chrome", "203.0.113.1");
        final String currentRaw = issueToken(userAlice, "fp-current", "Chrome", "203.0.113.1");

        mockMvc.perform(delete("/api/auth/devices/{deviceId}", 999_999_999L)
                .header("Authorization", "Bearer " + currentRaw))
            .andExpect(status().isNotFound());
    }

    @Test
    void deleteOne_returns403_whenNoBearerToken() throws Exception {
        mockMvc.perform(delete("/api/auth/devices/{deviceId}", 1L))
            .andExpect(status().isForbidden());
    }
}

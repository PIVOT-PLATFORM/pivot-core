package fr.pivot.account.controller;

import fr.pivot.AbstractIntegrationTest;
import fr.pivot.account.dto.ExportRequestedDto;
import fr.pivot.account.dto.ExportStatusDto;
import fr.pivot.account.entity.DataExportRequest;
import fr.pivot.account.repository.DataExportRequestRepository;
import fr.pivot.auth.entity.AuditEvent;
import fr.pivot.auth.entity.User;
import fr.pivot.auth.exception.RateLimitException;
import fr.pivot.auth.repository.AuditEventRepository;
import fr.pivot.auth.repository.UserRepository;
import fr.pivot.auth.service.EmailService;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.server.ResponseStatusException;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;

/**
 * Full-stack integration test (real PostgreSQL via Testcontainers, real Spring context) for
 * the RGPD Art. 20 personal-data export flow (US02.3.1).
 *
 * <p>{@code EmailService} is replaced by a Mockito mock ({@code @MockitoBean}) — no real SMTP
 * traffic — which also lets the test capture the raw one-time download token embedded in the
 * "export ready" email (the only place the raw token is ever exposed; only its SHA-256 hash is
 * persisted). Archive generation runs synchronously in tests via {@code
 * AbstractIntegrationTest.TestCacheConfig}'s {@code SyncTaskExecutor}.
 *
 * <p>Traceability:
 * <ul>
 *   <li>"Rate limit : 1 export / 24h par utilisateur" —
 *       {@code requestExport_returns429_whenWithin24hOfPreviousReadyExport},
 *       {@code requestExport_returns409_whenAPreviousRequestIsStillPending}</li>
 *   <li>"Tentative par un autre userId → 403" — {@code download_returns403_forNonOwner}</li>
 *   <li>"L'archive contient uniquement les données dont l'utilisateur est le sujet. Les audit
 *       events inclus ne contiennent pas de données personnelles d'autres utilisateurs" —
 *       {@code fullFlow_generatesDownloadableArchive_excludingOtherUsersPii}</li>
 * </ul>
 */
@DirtiesContext
class AccountExportIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private AccountExportController controller;

    @Autowired
    private DataExportRequestRepository exportRepo;

    @Autowired
    private UserRepository userRepo;

    @Autowired
    private AuditEventRepository auditEventRepo;

    @MockitoBean
    private EmailService emailService;

    private User owner;
    private User otherUser;

    @DynamicPropertySource
    static void exportStorage(final DynamicPropertyRegistry registry) throws IOException {
        final Path tempDir = Files.createTempDirectory("pivot-export-it");
        registry.add("pivot.export.storage-path", tempDir::toString);
    }

    @BeforeEach
    void setUp() {
        owner = userRepo.findByTenantIdAndEmailAndDeletedAtIsNull(1L, "user@pivot.test").orElseThrow();
        otherUser = userRepo.findByTenantIdAndEmailAndDeletedAtIsNull(1L, "admin@pivot.test").orElseThrow();
    }

    @AfterEach
    void tearDown() {
        exportRepo.deleteByUserId(owner.getId());
        exportRepo.deleteByUserId(otherUser.getId());
        SecurityContextHolder.clearContext();
    }

    @Test
    void requestExport_returns202_andEventuallyReady_downloadableByOwnerOnly() throws IOException {
        // A "leaked" admin PII marker planted on the OWNER's own audit row (defense-in-depth
        // scenario from the AC: a future event type could log a third party's email against the
        // subject's own user_id) and a marker on the OTHER user's row (proves query scoping).
        auditEventRepo.save(AuditEvent.of(owner, null, "account.blocked_by_admin", "203.0.113.9", "JUnit",
            "{\"performedBy\":\"admin-leak@pivot.internal\"}"));
        auditEventRepo.save(AuditEvent.of(otherUser, null, "auth.login", "203.0.113.10", "JUnit",
            "{\"marker\":\"other-user-secret-marker\"}"));

        setAuthentication(owner);
        final ResponseEntity<ExportRequestedDto> postResponse = controller.requestExport(fakeRequest());
        assertThat(postResponse.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(postResponse.getBody().status()).isEqualTo("PENDING");

        // Synchronous executor in tests: generation has already completed by the time
        // requestExport() returns. Confirm the persisted row reached READY.
        final ResponseEntity<ExportStatusDto> statusResponse = controller.status();
        assertThat(statusResponse.getBody().status()).isEqualTo("READY");
        assertThat(statusResponse.getBody().expiresAt()).isAfter(Instant.now());

        final ArgumentCaptor<String> tokenCaptor = ArgumentCaptor.forClass(String.class);
        verify(emailService).sendExportReadyEmail(eq(owner.getEmail()), anyString(), tokenCaptor.capture(), any(Locale.class));
        final String rawToken = tokenCaptor.getValue();

        // Owner can download.
        final ResponseEntity<ByteArrayResource> download = controller.download(rawToken);
        assertThat(download.getStatusCode()).isEqualTo(HttpStatus.OK);
        final byte[] zipBytes = download.getBody().getByteArray();

        final String auditJson = readZipEntry(zipBytes, "audit-events.json");
        final String sessionsJson = readZipEntry(zipBytes, "sessions.json");
        final String profilJson = readZipEntry(zipBytes, "profil.json");

        // MVP scope: profil, sessions, audit events — nothing else.
        assertThat(profilJson).contains(owner.getEmail());
        assertThat(sessionsJson).isNotNull();

        // PII exclusion: the admin-email-shaped marker planted on the owner's own row must be
        // redacted, and the other user's audit event must never appear at all.
        assertThat(auditJson)
            .doesNotContain("admin-leak@pivot.internal")
            .doesNotContain("other-user-secret-marker")
            .contains("account.blocked_by_admin");

        // Cross-user download: a different authenticated session must get 403, never the file.
        setAuthentication(otherUser);
        assertThatThrownBy(() -> controller.download(rawToken))
            .isInstanceOf(ResponseStatusException.class)
            .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN));
    }

    @Test
    void requestExport_returns409_whenAPreviousRequestIsStillPending() {
        final DataExportRequest pending = new DataExportRequest();
        pending.setUser(owner);
        exportRepo.save(pending);

        setAuthentication(owner);
        final HttpServletRequest req = fakeRequest();

        assertThatThrownBy(() -> controller.requestExport(req))
            .isInstanceOf(ResponseStatusException.class)
            .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode()).isEqualTo(HttpStatus.CONFLICT));
    }

    /**
     * Direct DB-level proof for the TOCTOU race Gate 4 review flagged: two near-simultaneous
     * {@code POST /api/account/export} calls could both pass the application-level
     * "no pending/processing row" check before either insert commits. This test bypasses that
     * application check entirely — inserting two {@code PENDING} rows back-to-back for the same
     * user, exactly as two racing requests would — and asserts the partial unique index
     * {@code idx_der_user_one_active} (migration V4) rejects the second one at the database
     * level, independently of the service-level check in {@code createPendingRequest}.
     */
    @Test
    void schema_shouldRejectConcurrentPendingRow_forSameUser() {
        final DataExportRequest first = new DataExportRequest();
        first.setUser(owner);
        exportRepo.saveAndFlush(first);

        final DataExportRequest concurrent = new DataExportRequest();
        concurrent.setUser(owner);

        assertThatThrownBy(() -> exportRepo.saveAndFlush(concurrent))
            .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void requestExport_returns429_whenWithin24hOfPreviousReadyExport() {
        setAuthentication(owner);
        controller.requestExport(fakeRequest()); // completes synchronously → READY
        final HttpServletRequest req = fakeRequest();

        assertThatThrownBy(() -> controller.requestExport(req))
            .isInstanceOf(RateLimitException.class)
            .satisfies(ex -> assertThat(((RateLimitException) ex).getRetryAfterSeconds()).isGreaterThan(0));
    }

    @Test
    void download_returns404_forUnknownToken() {
        setAuthentication(owner);
        assertThatThrownBy(() -> controller.download("00000000000000000000000000000000000000000"))
            .isInstanceOf(ResponseStatusException.class)
            .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND));
    }

    @Test
    void download_returns410_whenLinkExpired() {
        setAuthentication(owner);
        controller.requestExport(fakeRequest());

        final ArgumentCaptor<String> tokenCaptor = ArgumentCaptor.forClass(String.class);
        verify(emailService).sendExportReadyEmail(eq(owner.getEmail()), anyString(), tokenCaptor.capture(), any(Locale.class));

        // Force the link into the past to exercise the TTL check.
        final DataExportRequest req = exportRepo.findFirstByUserIdOrderByRequestedAtDesc(owner.getId()).orElseThrow();
        req.setExpiresAt(Instant.now().minus(1, ChronoUnit.HOURS));
        exportRepo.save(req);
        final String token = tokenCaptor.getValue();

        assertThatThrownBy(() -> controller.download(token))
            .isInstanceOf(ResponseStatusException.class)
            .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode()).isEqualTo(HttpStatus.GONE));
    }

    // ----------------------------------------------------------------
    // Helpers
    // ----------------------------------------------------------------

    private void setAuthentication(final User user) {
        final UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken(
            user.getEmail(), null, List.of(new SimpleGrantedAuthority(user.getRole())));
        auth.setDetails(user);
        SecurityContextHolder.getContext().setAuthentication(auth);
    }

    private HttpServletRequest fakeRequest() {
        final MockHttpServletRequest request = new MockHttpServletRequest("POST", "/account/export");
        request.setRemoteAddr("203.0.113.99");
        request.addHeader("User-Agent", "JUnit-IT");
        return request;
    }

    private static String readZipEntry(final byte[] zip, final String entryName) throws IOException {
        try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(zip), StandardCharsets.UTF_8)) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                if (entry.getName().equals(entryName)) {
                    return new String(zis.readAllBytes(), StandardCharsets.UTF_8);
                }
            }
        }
        return null;
    }
}

package fr.pivot.account.service;

import fr.pivot.account.dto.ExportAuditEventDto;
import fr.pivot.account.dto.ExportProfileDto;
import fr.pivot.account.dto.ExportSessionDto;
import fr.pivot.account.entity.DataExportRequest;
import fr.pivot.account.entity.DataExportStatus;
import fr.pivot.account.repository.DataExportRequestRepository;
import fr.pivot.auth.entity.AccessToken;
import fr.pivot.auth.entity.AuditEvent;
import fr.pivot.auth.entity.AuthMethod;
import fr.pivot.auth.entity.TokenStatus;
import fr.pivot.auth.entity.User;
import fr.pivot.auth.exception.RateLimitException;
import fr.pivot.auth.repository.AccessTokenRepository;
import fr.pivot.auth.repository.AuditEventRepository;
import fr.pivot.auth.repository.UserRepository;
import fr.pivot.auth.service.AuditService;
import fr.pivot.auth.service.EmailService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link DataExportService} — RGPD Art. 20 personal-data export (US02.3.1).
 *
 * <p>Mirrors {@code AuditServiceTest} / {@code TokenServiceTest}: the self-proxy field is a
 * plain mock — only {@link DataExportService#requestExport} and {@link
 * DataExportService#generateArchive} dispatch through it, so those two tests verify
 * orchestration via {@code verify(self, ...)} while every other method is exercised directly
 * (they never call {@code self}).
 *
 * <p>Traceability (AC "US02.3.1 — Export de ses données personnelles"):
 * <ul>
 *   <li>"Rate limit : 1 export / 24h par utilisateur" — {@code createPendingRequest_*}</li>
 *   <li>"Si demande en cours..." → 409 — {@code createPendingRequest_throws409_whenAlreadyInProgress}</li>
 *   <li>"Lien de téléchargement requiert session authentifiée ET vérifie userId... 403" —
 *       {@code resolveDownload_throws403_onOwnerMismatch}</li>
 *   <li>"TTL 24h" — {@code resolveDownload_throws410_whenExpired}</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DataExportServiceTest {

    @Mock private DataExportRequestRepository exportRepo;
    @Mock private UserRepository userRepo;
    @Mock private AccessTokenRepository accessTokenRepo;
    @Mock private AuditEventRepository auditEventRepo;
    @Mock private AuditService auditService;
    @Mock private EmailService emailService;
    @Mock private ExportArchiveBuilder archiveBuilder;
    @Mock private ExportStorageService storageService;
    @Mock private DataExportService self;

    @Mock private User user;
    @Mock private DataExportRequest previous;

    private DataExportService service;

    @BeforeEach
    void setUp() {
        service = new DataExportService(
            exportRepo, userRepo, accessTokenRepo, auditEventRepo, auditService, emailService,
            archiveBuilder, storageService, self, 24, 24);
    }

    // ----------------------------------------------------------------
    // createPendingRequest — rate limiting
    // ----------------------------------------------------------------

    @Test
    void createPendingRequest_succeeds_whenNoPreviousRequestExists() {
        when(user.getId()).thenReturn(1L);
        when(exportRepo.findFirstByUserIdOrderByRequestedAtDesc(1L)).thenReturn(Optional.empty());
        when(exportRepo.saveAndFlush(any(DataExportRequest.class))).thenAnswer(inv -> inv.getArgument(0));

        final DataExportRequest result = service.createPendingRequest(user);

        assertThat(result.getStatus()).isEqualTo(DataExportStatus.PENDING);
        assertThat(result.getUser()).isEqualTo(user);
        final ArgumentCaptor<DataExportRequest> captor = ArgumentCaptor.forClass(DataExportRequest.class);
        verify(exportRepo).saveAndFlush(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(DataExportStatus.PENDING);
    }

    @Test
    void createPendingRequest_throws409_whenAlreadyInProgress() {
        when(user.getId()).thenReturn(1L);
        when(previous.isInProgress()).thenReturn(true);
        when(exportRepo.findFirstByUserIdOrderByRequestedAtDesc(1L)).thenReturn(Optional.of(previous));

        assertThatThrownBy(() -> service.createPendingRequest(user))
            .isInstanceOf(ResponseStatusException.class)
            .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode())
                .isEqualTo(HttpStatus.CONFLICT));
        verify(exportRepo, never()).saveAndFlush(any());
    }

    @Test
    void createPendingRequest_throws429_whenLessThan24hElapsed() {
        when(user.getId()).thenReturn(1L);
        when(previous.isInProgress()).thenReturn(false);
        when(previous.getRequestedAt()).thenReturn(Instant.now().minus(2, ChronoUnit.HOURS));
        when(exportRepo.findFirstByUserIdOrderByRequestedAtDesc(1L)).thenReturn(Optional.of(previous));

        assertThatThrownBy(() -> service.createPendingRequest(user))
            .isInstanceOf(RateLimitException.class)
            .satisfies(ex -> assertThat(((RateLimitException) ex).getRetryAfterSeconds()).isGreaterThan(0));
        verify(exportRepo, never()).saveAndFlush(any());
    }

    @Test
    void createPendingRequest_succeeds_whenMoreThan24hElapsed() {
        when(user.getId()).thenReturn(1L);
        when(previous.isInProgress()).thenReturn(false);
        when(previous.getRequestedAt()).thenReturn(Instant.now().minus(25, ChronoUnit.HOURS));
        when(exportRepo.findFirstByUserIdOrderByRequestedAtDesc(1L)).thenReturn(Optional.of(previous));
        when(exportRepo.saveAndFlush(any(DataExportRequest.class))).thenAnswer(inv -> inv.getArgument(0));

        final DataExportRequest result = service.createPendingRequest(user);

        assertThat(result.getStatus()).isEqualTo(DataExportStatus.PENDING);
    }

    @Test
    void createPendingRequest_throws409_whenConcurrentInsertRaceLosesToDbConstraint() {
        // Simulates the TOCTOU race: the application-level check above passed (no previous
        // request read), but a concurrent request's insert committed first, so the partial
        // unique index (idx_der_user_one_active, migration V4) rejects this insert.
        when(user.getId()).thenReturn(1L);
        when(exportRepo.findFirstByUserIdOrderByRequestedAtDesc(1L)).thenReturn(Optional.empty());
        when(exportRepo.saveAndFlush(any(DataExportRequest.class)))
            .thenThrow(new DataIntegrityViolationException("idx_der_user_one_active"));

        assertThatThrownBy(() -> service.createPendingRequest(user))
            .isInstanceOf(ResponseStatusException.class)
            .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode())
                .isEqualTo(HttpStatus.CONFLICT));
    }

    // ----------------------------------------------------------------
    // nextAvailableAt
    // ----------------------------------------------------------------

    @Test
    void nextAvailableAt_returnsNull_whenNoLastRequest() {
        assertThat(service.nextAvailableAt(null)).isNull();
    }

    @Test
    void nextAvailableAt_returnsNull_whenInProgress() {
        when(previous.isInProgress()).thenReturn(true);
        assertThat(service.nextAvailableAt(previous)).isNull();
    }

    @Test
    void nextAvailableAt_returnsFutureInstant_whenWithinRateLimitWindow() {
        when(previous.isInProgress()).thenReturn(false);
        when(previous.getRequestedAt()).thenReturn(Instant.now().minus(1, ChronoUnit.HOURS));

        assertThat(service.nextAvailableAt(previous)).isAfter(Instant.now());
    }

    @Test
    void nextAvailableAt_returnsNull_whenWindowElapsed() {
        when(previous.isInProgress()).thenReturn(false);
        when(previous.getRequestedAt()).thenReturn(Instant.now().minus(30, ChronoUnit.HOURS));

        assertThat(service.nextAvailableAt(previous)).isNull();
    }

    // ----------------------------------------------------------------
    // requestExport — orchestration (audit log + async trigger), self is a mock
    // ----------------------------------------------------------------

    @Test
    void requestExport_logsAuditEvent_andTriggersGenerationThroughSelf() {
        when(user.getLocale()).thenReturn("fr");
        final DataExportRequest created = new DataExportRequest();
        created.setUser(user);
        when(self.createPendingRequest(user)).thenReturn(created);

        final DataExportRequest result = service.requestExport(user, "1.2.3.4", "JUnit");

        assertThat(result).isSameAs(created);
        verify(auditService).log(user, AuditService.DATA_EXPORT_REQUESTED, "1.2.3.4", "JUnit");
        verify(self).generateArchive(created.getId(), Locale.FRENCH);
    }

    // ----------------------------------------------------------------
    // loadBundle
    // ----------------------------------------------------------------

    @Test
    void loadBundle_marksProcessing_andAssemblesSections() {
        final DataExportRequest request = new DataExportRequest();
        request.setUser(user);
        when(exportRepo.findById(42L)).thenReturn(Optional.of(request));
        when(user.getId()).thenReturn(7L);
        when(user.getEmail()).thenReturn("owner@pivot.test");
        when(user.getFirstName()).thenReturn("Ada");
        when(userRepo.findById(7L)).thenReturn(Optional.of(user));

        final AccessToken session = new AccessToken();
        session.setDeviceName("Chrome / macOS");
        session.setAuthMethod(AuthMethod.PASSWORD);
        session.setStatus(TokenStatus.ACTIVE);
        when(accessTokenRepo.findByUserIdOrderByCreatedAtDesc(7L)).thenReturn(List.of(session));

        final AuditEvent event = AuditEvent.of(user, null, "auth.login", "9.9.9.9", "UA", null);
        when(auditEventRepo.findByUserIdOrderByCreatedAtDesc(7L)).thenReturn(List.of(event));

        final DataExportService.ExportBundle bundle = service.loadBundle(42L);

        assertThat(request.getStatus()).isEqualTo(DataExportStatus.PROCESSING);
        assertThat(bundle.userId()).isEqualTo(7L);
        assertThat(bundle.email()).isEqualTo("owner@pivot.test");
        assertThat(bundle.sessions()).hasSize(1);
        assertThat(bundle.auditEvents()).hasSize(1);
        verify(exportRepo).save(request);
    }

    // ----------------------------------------------------------------
    // generateArchive — orchestration via a mocked self (loadBundle/markReady/markFailedAndNotify)
    // ----------------------------------------------------------------

    @Test
    void generateArchive_onSuccess_marksReadyThroughSelf_andSendsEmail() throws IOException {
        final DataExportService.ExportBundle bundle = new DataExportService.ExportBundle(
            7L, "owner@pivot.test", "Ada",
            new ExportProfileDto(7L, "owner@pivot.test", "Ada", "Lovelace", "ROLE_USER", "fr",
                true, null, "PIVOT SaaS", Instant.now(), Instant.now()),
            List.<ExportSessionDto>of(), List.<ExportAuditEventDto>of());
        when(self.loadBundle(42L)).thenReturn(bundle);
        final byte[] zipBytes = {1, 2, 3};
        when(archiveBuilder.build(bundle.profile(), bundle.sessions(), bundle.auditEvents())).thenReturn(zipBytes);
        when(storageService.store(7L, 42L, zipBytes)).thenReturn("/tmp/export-42.zip");

        service.generateArchive(42L, Locale.FRENCH);

        verify(self).markReady(eq(42L), eq("/tmp/export-42.zip"), eq(3L), anyString());
        verify(emailService).sendExportReadyEmail(eq("owner@pivot.test"), eq("Ada"), anyString(), eq(Locale.FRENCH));
        verify(self, never()).markFailedAndNotify(any(), any(), any());
    }

    @Test
    void generateArchive_onStorageFailure_marksFailedThroughSelf_andSkipsEmail() throws IOException {
        final DataExportService.ExportBundle bundle = new DataExportService.ExportBundle(
            7L, "owner@pivot.test", "Ada",
            new ExportProfileDto(7L, "owner@pivot.test", "Ada", "Lovelace", "ROLE_USER", "fr",
                true, null, "PIVOT SaaS", Instant.now(), Instant.now()),
            List.<ExportSessionDto>of(), List.<ExportAuditEventDto>of());
        when(self.loadBundle(42L)).thenReturn(bundle);
        when(archiveBuilder.build(any(), any(), any())).thenReturn(new byte[]{1});
        when(storageService.store(eq(7L), eq(42L), any())).thenThrow(new IOException("disk full"));

        service.generateArchive(42L, Locale.FRENCH);

        verify(self).markFailedAndNotify(eq(42L), org.mockito.ArgumentMatchers.contains("disk full"), eq(Locale.FRENCH));
        verify(self, never()).markReady(any(), any(), any(), any());
        verify(emailService, never()).sendExportReadyEmail(any(), any(), any(), any());
    }

    // ----------------------------------------------------------------
    // markReady / markFailedAndNotify
    // ----------------------------------------------------------------

    @Test
    void markReady_setsStatusTokenAndExpiry() {
        final DataExportRequest request = new DataExportRequest();
        when(exportRepo.findById(42L)).thenReturn(Optional.of(request));

        service.markReady(42L, "/tmp/f.zip", 123L, "raw-token-value");

        assertThat(request.getStatus()).isEqualTo(DataExportStatus.READY);
        assertThat(request.getFilePath()).isEqualTo("/tmp/f.zip");
        assertThat(request.getFileSizeBytes()).isEqualTo(123L);
        assertThat(request.getTokenHash()).isNotEqualTo("raw-token-value").isNotBlank();
        assertThat(request.getExpiresAt()).isAfter(Instant.now());
        verify(exportRepo).save(request);
    }

    @Test
    void markFailedAndNotify_setsFailedStatus_andEmailsUser() {
        final DataExportRequest request = new DataExportRequest();
        request.setUser(user);
        when(user.getEmail()).thenReturn("owner@pivot.test");
        when(user.getFirstName()).thenReturn("Ada");
        when(exportRepo.findById(42L)).thenReturn(Optional.of(request));

        service.markFailedAndNotify(42L, "boom", Locale.FRENCH);

        assertThat(request.getStatus()).isEqualTo(DataExportStatus.FAILED);
        assertThat(request.getErrorMessage()).isEqualTo("boom");
        verify(emailService).sendExportFailedEmail("owner@pivot.test", "Ada", Locale.FRENCH);
    }

    @Test
    void markFailedAndNotify_isNoOp_whenRequestNoLongerExists() {
        when(exportRepo.findById(99L)).thenReturn(Optional.empty());

        service.markFailedAndNotify(99L, "boom", Locale.FRENCH);

        verify(exportRepo, never()).save(any());
        verify(emailService, never()).sendExportFailedEmail(any(), any(), any());
    }

    // ----------------------------------------------------------------
    // resolveDownload — ownership + expiry
    // ----------------------------------------------------------------

    @Test
    void resolveDownload_throws404_whenTokenUnknown() {
        when(exportRepo.findByTokenHashWithUser(any())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.resolveDownload("does-not-exist", 1L))
            .isInstanceOf(ResponseStatusException.class)
            .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND));
    }

    @Test
    void resolveDownload_throws403_onOwnerMismatch() {
        final DataExportRequest request = new DataExportRequest();
        request.setUser(user);
        when(user.getId()).thenReturn(1L);
        when(exportRepo.findByTokenHashWithUser(any())).thenReturn(Optional.of(request));

        assertThatThrownBy(() -> service.resolveDownload("raw-token", 999L))
            .isInstanceOf(ResponseStatusException.class)
            .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN));
    }

    @Test
    void resolveDownload_throws410_whenExpired() {
        final DataExportRequest request = new DataExportRequest();
        request.setUser(user);
        request.setExpiresAt(Instant.now().minus(1, ChronoUnit.HOURS));
        when(user.getId()).thenReturn(1L);
        when(exportRepo.findByTokenHashWithUser(any())).thenReturn(Optional.of(request));

        assertThatThrownBy(() -> service.resolveDownload("raw-token", 1L))
            .isInstanceOf(ResponseStatusException.class)
            .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode())
                .isEqualTo(HttpStatus.GONE));
    }

    @Test
    void resolveDownload_succeeds_forOwnerWithValidLink() {
        final DataExportRequest request = new DataExportRequest();
        request.setUser(user);
        request.setExpiresAt(Instant.now().plus(1, ChronoUnit.HOURS));
        when(user.getId()).thenReturn(1L);
        when(exportRepo.findByTokenHashWithUser(any())).thenReturn(Optional.of(request));

        assertThat(service.resolveDownload("raw-token", 1L)).isSameAs(request);
    }
}

package fr.pivot.account.service;

import fr.pivot.account.dto.ExportAuditEventDto;
import fr.pivot.account.dto.ExportProfileDto;
import fr.pivot.account.dto.ExportSessionDto;
import fr.pivot.account.entity.DataExportRequest;
import fr.pivot.account.entity.DataExportStatus;
import fr.pivot.account.repository.DataExportRequestRepository;
import fr.pivot.auth.entity.User;
import fr.pivot.auth.exception.RateLimitException;
import fr.pivot.auth.repository.AccessTokenRepository;
import fr.pivot.auth.repository.AuditEventRepository;
import fr.pivot.auth.repository.UserRepository;
import fr.pivot.auth.service.AuditService;
import fr.pivot.auth.service.EmailService;
import fr.pivot.auth.util.CryptoUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Orchestrates the RGPD Art. 20 personal-data export flow (US02.3.1): rate limiting, async
 * archive generation (profil + sessions + audit events), storage and the download-link email.
 *
 * <p>Generation runs on a plain Spring {@code @Async} method — no message broker is warranted
 * for a low-volume, single-user background job (this is the first async/background feature in
 * pivot-core; {@code @EnableAsync} is already declared on {@code AppConfig} for
 * {@link AuditService} / {@link fr.pivot.auth.service.TokenService}).
 *
 * <p>Self-proxy pattern ({@link #self}) mirrors {@link AuditService} / {@link
 * fr.pivot.auth.service.TokenService}: a direct {@code this.xxx(...)} call would bypass the
 * Spring proxy, disabling the {@code @Async} / {@code @Transactional} semantics.
 */
@Service
public class DataExportService {

    private static final Logger LOG = LoggerFactory.getLogger(DataExportService.class);

    private final DataExportRequestRepository exportRepo;
    private final UserRepository userRepo;
    private final AccessTokenRepository accessTokenRepo;
    private final AuditEventRepository auditEventRepo;
    private final AuditService auditService;
    private final EmailService emailService;
    private final ExportArchiveBuilder archiveBuilder;
    private final ExportStorageService storageService;
    private final DataExportService self;
    private final int rateLimitHours;
    private final int downloadTtlHours;

    /**
     * Constructs the service with its required collaborators.
     *
     * @param exportRepo       JPA repository for {@link DataExportRequest}
     * @param userRepo         JPA repository for {@link User}
     * @param accessTokenRepo  JPA repository for sessions (the "sessions" export section)
     * @param auditEventRepo   JPA repository for audit events (the "audit events" export section)
     * @param auditService     async audit event logger ({@code DataExportRequested})
     * @param emailService     transactional email sender (download-link + failure notice)
     * @param archiveBuilder   ZIPs the profil/sessions/audit-events JSON payloads
     * @param storageService   local-filesystem storage for the generated archive
     * @param self             self proxy for async/transactional dispatch
     * @param rateLimitHours   minimum delay between two export requests (default 24h)
     * @param downloadTtlHours download-link validity once the archive is ready (default 24h)
     */
    public DataExportService(
            final DataExportRequestRepository exportRepo,
            final UserRepository userRepo,
            final AccessTokenRepository accessTokenRepo,
            final AuditEventRepository auditEventRepo,
            final AuditService auditService,
            final EmailService emailService,
            final ExportArchiveBuilder archiveBuilder,
            final ExportStorageService storageService,
            final @Lazy DataExportService self,
            @Value("${pivot.export.rate-limit-hours:24}") final int rateLimitHours,
            @Value("${pivot.export.download-ttl-hours:24}") final int downloadTtlHours) {
        this.exportRepo = exportRepo;
        this.userRepo = userRepo;
        this.accessTokenRepo = accessTokenRepo;
        this.auditEventRepo = auditEventRepo;
        this.auditService = auditService;
        this.emailService = emailService;
        this.archiveBuilder = archiveBuilder;
        this.storageService = storageService;
        this.self = self;
        this.rateLimitHours = rateLimitHours;
        this.downloadTtlHours = downloadTtlHours;
    }

    /**
     * Handles {@code POST /api/account/export}: validates the rate limit, persists a
     * {@code PENDING} row, logs the {@code DataExportRequested} audit event, then triggers
     * archive generation asynchronously.
     *
     * @param user      the authenticated requester — always the export owner, never accepted
     *                  from a request body (mass-assignment / IDOR hard rule)
     * @param ip        client IP for the audit trail
     * @param userAgent client user-agent for the audit trail
     * @return the freshly created {@code PENDING} request
     * @throws ResponseStatusException {@code 409} if a request is already pending/processing
     * @throws RateLimitException      {@code 429} if less than {@code rate-limit-hours} elapsed
     *                                 since the last request
     */
    public DataExportRequest requestExport(final User user, final String ip, final String userAgent) {
        final DataExportRequest request = self.createPendingRequest(user);
        auditService.log(user, AuditService.DATA_EXPORT_REQUESTED, ip, userAgent);
        self.generateArchive(request.getId(), EmailService.toLocale(user.getLocale()));
        return request;
    }

    /**
     * Rate-limit check + {@code PENDING} row creation, in its own transaction so the row is
     * committed and visible before {@link #generateArchive} (an {@code @Async} method running on
     * a separate thread) reads it back.
     *
     * <p>The {@code isInProgress()} check above and the insert below are not atomic: two
     * near-simultaneous requests for the same user could both read "no pending/processing row"
     * before either commits. The partial unique index {@code idx_der_user_one_active} (migration
     * V4) closes this window at the database level — the losing insert raises {@link
     * DataIntegrityViolationException}, caught here and translated to the same {@code 409} the
     * application-level check already returns, so the race is invisible to the caller.
     *
     * @param user the export owner
     * @return the persisted {@code PENDING} request
     * @throws ResponseStatusException {@code 409} if a request is already pending/processing,
     *                                 whether observed by the check above or by the DB constraint
     *                                 rejecting a concurrent insert
     */
    @Transactional
    public DataExportRequest createPendingRequest(final User user) {
        final Optional<DataExportRequest> last = exportRepo.findFirstByUserIdOrderByRequestedAtDesc(user.getId());
        if (last.isPresent()) {
            final DataExportRequest previous = last.get();
            if (previous.isInProgress()) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Un export est déjà en cours de génération");
            }
            final Instant nextAvailableAt = previous.getRequestedAt().plus(rateLimitHours, ChronoUnit.HOURS);
            if (nextAvailableAt.isAfter(Instant.now())) {
                throw new RateLimitException(Duration.between(Instant.now(), nextAvailableAt).getSeconds());
            }
        }

        final DataExportRequest request = new DataExportRequest();
        request.setUser(user);
        request.setStatus(DataExportStatus.PENDING);
        try {
            exportRepo.saveAndFlush(request);
        } catch (final DataIntegrityViolationException _) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Un export est déjà en cours de génération");
        }
        LOG.info("event=DATA_EXPORT_REQUESTED userId={} requestId={}", user.getId(), request.getId());
        return request;
    }

    /**
     * Returns the latest export request for a user — feeds {@code GET /api/account/export/status},
     * which lets the frontend render the disabled/enabled button state up front, without first
     * attempting a {@code POST}.
     *
     * @param userId the user to look up
     * @return the latest request, or empty if the user never requested an export
     */
    @Transactional(readOnly = true)
    public Optional<DataExportRequest> findLatest(final Long userId) {
        return exportRepo.findFirstByUserIdOrderByRequestedAtDesc(userId);
    }

    /**
     * Computes the timestamp at which a new export may be requested.
     *
     * @param last the latest known request, or {@code null} if none exists
     * @return the next-available instant, or {@code null} if a request may be made right now
     *         (including while one is already in progress — that case is reported via
     *         {@code status = PENDING/PROCESSING} instead of a time-based wait)
     */
    public Instant nextAvailableAt(final DataExportRequest last) {
        if (last == null || last.isInProgress()) {
            return null;
        }
        final Instant candidate = last.getRequestedAt().plus(rateLimitHours, ChronoUnit.HOURS);
        return candidate.isAfter(Instant.now()) ? candidate : null;
    }

    /**
     * Generates the archive asynchronously: builds the profil/sessions/audit-events payload,
     * ZIPs it, stores it, issues a one-time download token, marks the request {@code READY} and
     * emails the download link. Any failure marks the request {@code FAILED} and notifies the
     * user by email so they are not left waiting indefinitely.
     *
     * @param requestId the {@link DataExportRequest} primary key
     * @param locale    the recipient's preferred locale, captured before the async hop
     */
    @Async
    public void generateArchive(final Long requestId, final Locale locale) {
        try {
            final ExportBundle bundle = self.loadBundle(requestId);
            final byte[] zip = archiveBuilder.build(bundle.profile(), bundle.sessions(), bundle.auditEvents());
            final String path = storageService.store(bundle.userId(), requestId, zip);
            final String rawToken = CryptoUtils.generateSecureToken();
            self.markReady(requestId, path, (long) zip.length, rawToken);
            emailService.sendExportReadyEmail(bundle.email(), bundle.firstName(), rawToken, locale);
            LOG.info("event=DATA_EXPORT_READY requestId={} userId={}", requestId, bundle.userId());
        } catch (final IOException | RuntimeException e) {
            LOG.error("event=DATA_EXPORT_FAILED requestId={} error={}", requestId, e.getMessage());
            self.markFailedAndNotify(requestId, e.getMessage(), locale);
        }
    }

    /**
     * Marks the request {@code PROCESSING} and loads everything needed to build the archive.
     *
     * @param requestId the request to process
     * @return the data bundle (profil, sessions, audit events) plus notification metadata
     */
    @Transactional
    public ExportBundle loadBundle(final Long requestId) {
        final DataExportRequest request = exportRepo.findById(requestId)
            .orElseThrow(() -> new IllegalStateException("Export request not found: " + requestId));
        request.setStatus(DataExportStatus.PROCESSING);
        exportRepo.save(request);

        final User user = userRepo.findById(request.getUser().getId())
            .orElseThrow(() -> new IllegalStateException("Export owner not found: " + requestId));

        final List<ExportSessionDto> sessions = accessTokenRepo.findByUserIdOrderByCreatedAtDesc(user.getId())
            .stream().map(ExportSessionDto::from).toList();
        // Scoped to WHERE user_id = <owner> — audit_events.user_id is always the actor, so this
        // can only ever return the owner's own actions (see ExportAuditEventDto javadoc).
        final List<ExportAuditEventDto> auditEvents = auditEventRepo.findByUserIdOrderByCreatedAtDesc(user.getId())
            .stream().map(ExportAuditEventDto::from).toList();

        return new ExportBundle(user.getId(), user.getEmail(), user.getFirstName(),
            ExportProfileDto.from(user), sessions, auditEvents);
    }

    /**
     * Marks a request {@code READY} with the archive's storage path and download-token hash.
     *
     * @param requestId     the request to complete
     * @param filePath      absolute path of the stored ZIP archive
     * @param fileSizeBytes archive size in bytes
     * @param rawToken      the raw (unhashed) one-time download token — never persisted
     */
    @Transactional
    public void markReady(final Long requestId, final String filePath, final Long fileSizeBytes, final String rawToken) {
        final DataExportRequest request = exportRepo.findById(requestId)
            .orElseThrow(() -> new IllegalStateException("Export request not found: " + requestId));
        request.setStatus(DataExportStatus.READY);
        request.setFilePath(filePath);
        request.setFileSizeBytes(fileSizeBytes);
        request.setTokenHash(CryptoUtils.sha256(rawToken));
        final Instant now = Instant.now();
        request.setCompletedAt(now);
        request.setExpiresAt(now.plus(downloadTtlHours, ChronoUnit.HOURS));
        exportRepo.save(request);
    }

    /**
     * Marks a request {@code FAILED} and notifies the user by email so they are not left
     * waiting for a download link that will never arrive. Silent no-op if the request row
     * itself is gone (should not normally happen).
     *
     * @param requestId    the request that failed
     * @param errorMessage a short diagnostic message (never shown verbatim to the user)
     * @param locale       the recipient's preferred locale
     */
    @Transactional
    public void markFailedAndNotify(final Long requestId, final String errorMessage, final Locale locale) {
        final DataExportRequest request = exportRepo.findById(requestId).orElse(null);
        if (request == null) {
            return;
        }
        request.setStatus(DataExportStatus.FAILED);
        request.setErrorMessage(errorMessage);
        request.setCompletedAt(Instant.now());
        exportRepo.save(request);

        final User user = request.getUser();
        if (user != null && user.getEmail() != null) {
            emailService.sendExportFailedEmail(user.getEmail(), user.getFirstName(), locale);
        }
    }

    /**
     * Resolves a raw download token to its owning request, enforcing ownership and expiry.
     *
     * <p>Ownership is checked before expiry so a cross-user attempt always gets {@code 403}
     * regardless of whether the link happens to also be expired (never leak expiry state to a
     * non-owner).
     *
     * @param rawToken          the raw token from {@code /export/download/{exportToken}}
     * @param requestingUserId  the userId of the currently authenticated session
     * @return the resolved, downloadable {@link DataExportRequest}
     * @throws ResponseStatusException {@code 404} unknown/not-ready token, {@code 403} owner
     *                                 mismatch, {@code 410} expired link
     */
    @Transactional(readOnly = true)
    public DataExportRequest resolveDownload(final String rawToken, final Long requestingUserId) {
        final DataExportRequest request = exportRepo.findByTokenHashWithUser(CryptoUtils.sha256(rawToken))
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Export introuvable"));

        if (!request.getUser().getId().equals(requestingUserId)) {
            LOG.warn("event=DATA_EXPORT_DOWNLOAD_DENIED requestId={} ownerUserId={} requestingUserId={}",
                request.getId(), request.getUser().getId(), requestingUserId);
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Cet export ne vous appartient pas");
        }
        if (request.isExpired()) {
            throw new ResponseStatusException(HttpStatus.GONE, "Lien de téléchargement expiré");
        }
        return request;
    }

    /**
     * In-memory carrier for the data assembled by {@link #loadBundle} — avoids passing detached
     * JPA entities across the {@code @Async} boundary.
     */
    public record ExportBundle(
        Long userId,
        String email,
        String firstName,
        ExportProfileDto profile,
        List<ExportSessionDto> sessions,
        List<ExportAuditEventDto> auditEvents) {
    }
}

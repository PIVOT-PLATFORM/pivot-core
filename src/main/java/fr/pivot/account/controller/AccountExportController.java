package fr.pivot.account.controller;

import fr.pivot.account.dto.ExportRequestedDto;
import fr.pivot.account.dto.ExportStatusDto;
import fr.pivot.account.entity.DataExportRequest;
import fr.pivot.account.service.DataExportService;
import fr.pivot.account.service.ExportStorageService;
import fr.pivot.auth.entity.User;
import fr.pivot.config.CookieHelper;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.util.Optional;

/**
 * REST controller for the RGPD Art. 20 personal-data export flow (US02.3.1).
 *
 * <p>All business logic (rate limiting, generation orchestration, ownership/expiry checks)
 * lives in {@link DataExportService} — this controller only extracts the authenticated
 * {@link User} (posed by {@code TokenAuthenticationFilter} in the security context details,
 * same pattern as {@link fr.pivot.modules.api.ModuleController}) and translates to/from HTTP.
 *
 * <p>The {@code userId} used everywhere below always comes from the authenticated session —
 * never from a path/query/body parameter (mass-assignment / IDOR hard rule for
 * {@code /api/account/*} endpoints).
 */
@RestController
@RequestMapping("/account/export")
public class AccountExportController {

    private static final Logger LOG = LoggerFactory.getLogger(AccountExportController.class);
    private static final String HEADER_USER_AGENT = "User-Agent";

    /** Reported by {@code GET /status} when the user has never requested an export. */
    private static final String STATUS_NONE = "NONE";

    private final DataExportService exportService;
    private final ExportStorageService storageService;
    private final CookieHelper cookieHelper;

    /**
     * Constructs the controller with its required service collaborators.
     *
     * @param exportService  orchestrates rate limiting, generation and download resolution
     * @param storageService reads the generated archive bytes for download streaming
     * @param cookieHelper   shared client-IP resolution helper
     */
    public AccountExportController(final DataExportService exportService,
                                    final ExportStorageService storageService,
                                    final CookieHelper cookieHelper) {
        this.exportService = exportService;
        this.storageService = storageService;
        this.cookieHelper = cookieHelper;
    }

    /**
     * Triggers asynchronous generation of a personal-data export archive.
     *
     * @param http incoming request (IP, User-Agent extraction for the audit trail)
     * @return {@code 202 Accepted} with the newly created {@code PENDING} request
     * @throws ResponseStatusException {@code 409} if a request is already pending/processing
     * @throws fr.pivot.auth.exception.RateLimitException {@code 429} if less than 24h elapsed
     *         since the last request (translated by {@link fr.pivot.auth.web.GlobalExceptionHandler})
     */
    @PostMapping
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<ExportRequestedDto> requestExport(final HttpServletRequest http) {
        final User user = currentUser();
        final DataExportRequest request = exportService.requestExport(
            user, cookieHelper.clientIp(http), http.getHeader(HEADER_USER_AGENT));
        return ResponseEntity.status(HttpStatus.ACCEPTED)
            .body(new ExportRequestedDto(request.getId(), request.getStatus().name(), request.getRequestedAt()));
    }

    /**
     * Returns the authenticated user's latest export status — lets the frontend render the
     * "Demander mon export" button's disabled/enabled state (with the "next available at" hint)
     * without first attempting a {@code POST}.
     *
     * @return {@code 200 OK} with the current {@link ExportStatusDto}
     */
    @GetMapping("/status")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<ExportStatusDto> status() {
        final User user = currentUser();
        final Optional<DataExportRequest> last = exportService.findLatest(user.getId());
        final DataExportRequest req = last.orElse(null);
        final String status = req == null ? STATUS_NONE : req.getStatus().name();
        return ResponseEntity.ok(new ExportStatusDto(
            status,
            req == null ? null : req.getRequestedAt(),
            req == null ? null : req.getCompletedAt(),
            req == null ? null : req.getExpiresAt(),
            exportService.nextAvailableAt(req)));
    }

    /**
     * Downloads a previously generated export archive.
     *
     * <p>Authenticated endpoint — never a public presigned URL. {@link DataExportService#resolveDownload}
     * verifies the requesting session's userId matches the export's owner userId ({@code 403} on
     * mismatch) and that the 24h download-link TTL has not elapsed ({@code 410}).
     *
     * @param exportToken the raw one-time download token from the export-ready email link
     * @return {@code 200 OK} streaming the ZIP archive as {@code application/octet-stream}
     */
    @GetMapping("/download/{exportToken}")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<ByteArrayResource> download(@PathVariable final String exportToken) {
        final User user = currentUser();
        final DataExportRequest request = exportService.resolveDownload(exportToken, user.getId());

        final byte[] content;
        try {
            content = storageService.read(request.getFilePath());
        } catch (final IOException e) {
            LOG.error("event=DATA_EXPORT_DOWNLOAD_READ_FAILED requestId={} error={}",
                request.getId(), e.getMessage());
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Impossible de lire l'archive");
        }

        LOG.info("event=DATA_EXPORT_DOWNLOADED requestId={} userId={}", request.getId(), user.getId());
        return ResponseEntity.ok()
            .contentType(MediaType.APPLICATION_OCTET_STREAM)
            .header(HttpHeaders.CONTENT_DISPOSITION,
                "attachment; filename=\"pivot-export-" + request.getId() + ".zip\"")
            .body(new ByteArrayResource(content));
    }

    // ----------------------------------------------------------------
    // Private helpers
    // ----------------------------------------------------------------

    private User currentUser() {
        final Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (!(auth.getDetails() instanceof User user)) {
            LOG.warn("event=ACCOUNT_EXPORT_REJECTED reason=invalid_auth_details");
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        }
        return user;
    }
}

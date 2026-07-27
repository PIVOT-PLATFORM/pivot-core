package fr.pivot.collaboratif.meeting.report;

import fr.pivot.collaboratif.context.CollaboratifRequestPrincipal;
import fr.pivot.collaboratif.web.CollaboratifApiPaths;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * REST controller exposing the MeetOps meeting compte-rendu under {@code
 * /collaboratif/meetings/{meetingId}/report} (US12.3.1).
 *
 * <p>Requires a valid {@code Authorization: Bearer <token>} header, resolved into a {@link
 * CollaboratifRequestPrincipal} the same way as every other controller in this module. No
 * business logic lives here — every method is a thin pass-through to {@link
 * MeetingReportService}, which owns tenant/visibility resolution via {@code MeetingAccessService}.
 *
 * <p>Named {@code "meetingReportController"} explicitly (rather than relying on the default
 * decapitalized-simple-name bean id) to avoid any future collision, mirroring the precedent this
 * module already set for {@code QuizController}/{@code VoteController} pairs (see this module's
 * own bean-naming convention note).
 *
 * <p>The full path (including the application context) is {@code
 * /api/collaboratif/meetings/{meetingId}/report}.
 */
@RestController("meetingReportController")
@RequestMapping(CollaboratifApiPaths.BASE + "/meetings/{meetingId}/report")
public class MeetingReportController {

    private static final MediaType TEXT_MARKDOWN = MediaType.parseMediaType("text/markdown");

    private final MeetingReportService reportService;

    /**
     * Creates the controller with its required service dependency.
     *
     * @param reportService the meeting report business logic service
     */
    public MeetingReportController(final MeetingReportService reportService) {
        this.reportService = reportService;
    }

    /**
     * Returns the compte-rendu — the frozen snapshot for a closed meeting, or a live draft
     * otherwise (AC nominal).
     *
     * @param meetingId the meeting's UUID
     * @param principal the resolved caller identity
     * @return the report DTO, serialized as JSON
     */
    @GetMapping
    public MeetingReportDto get(
            @PathVariable final UUID meetingId, final CollaboratifRequestPrincipal principal) {
        return reportService.buildReport(meetingId, principal);
    }

    /**
     * Exports the compte-rendu as either native JSON (default) or Markdown (AC nominal).
     *
     * @param meetingId the meeting's UUID
     * @param format    {@code "json"} (default) or {@code "markdown"} — any other value is
     *                  rejected with {@code 400} before any report body is built
     * @param principal the resolved caller identity
     * @return {@code 200} with either the JSON DTO or a {@code text/markdown} body
     */
    @GetMapping("/export")
    public ResponseEntity<?> export(
            @PathVariable final UUID meetingId,
            @RequestParam(required = false) final String format,
            final CollaboratifRequestPrincipal principal) {
        String normalized = reportService.normalizeExportFormat(format);
        MeetingReportDto report = reportService.buildReport(meetingId, principal);
        if ("markdown".equals(normalized)) {
            return ResponseEntity.ok().contentType(TEXT_MARKDOWN).body(reportService.exportMarkdown(report));
        }
        return ResponseEntity.ok(report);
    }
}

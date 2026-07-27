package fr.pivot.collaboratif.meeting;

import fr.pivot.collaboratif.context.CollaboratifRequestPrincipal;
import fr.pivot.collaboratif.meeting.dto.CreateMeetingRequest;
import fr.pivot.collaboratif.meeting.dto.MeetingResponse;
import fr.pivot.collaboratif.web.CollaboratifApiPaths;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;

/**
 * REST controller exposing MeetOps meeting creation under {@code /collaboratif/meetings}
 * (US12.1.1).
 *
 * <p>Requires a valid {@code Authorization: Bearer <token>} header, resolved into a {@link
 * CollaboratifRequestPrincipal} by {@code CollaboratifRequestPrincipalResolver} (EN08.3). Missing,
 * malformed, or rejected tokens result in HTTP 401. Tenant and user identity always come from the
 * resolved principal — never from the request body (tenant isolation, EN08.3 / anti-IDOR, AC8).
 *
 * <p>The full path (including the application context) is {@code /api/collaboratif/meetings}.
 *
 * <p>This US covers creation only — no read/list/update/delete endpoint exists yet (see the
 * pivot-docs "Hors périmètre" section for US12.1.1).
 */
@RestController
@RequestMapping(CollaboratifApiPaths.BASE + "/meetings")
public class MeetingController {

    /**
     * Servlet-context-path-qualified prefix used to build the {@code Location} header (AC1) —
     * the application's global {@code server.servlet.context-path} ({@code /api}) plus this
     * controller's own {@link CollaboratifApiPaths#BASE} segment.
     */
    private static final String LOCATION_PREFIX = "/api" + CollaboratifApiPaths.BASE + "/meetings/";

    private final MeetingService meetingService;

    /**
     * Creates the controller with its required service dependency.
     *
     * @param meetingService the meeting business logic service
     */
    public MeetingController(final MeetingService meetingService) {
        this.meetingService = meetingService;
    }

    /**
     * Creates a new meeting with its agenda (US12.1.1 AC1).
     *
     * @param request   the creation request — title, scheduled date/time, total duration,
     *                  optional team, optional agenda items
     * @param principal the resolved caller identity (user + tenant)
     * @return HTTP 201 with the {@code Location} header and the created meeting
     */
    @PostMapping
    public ResponseEntity<MeetingResponse> create(
            @Valid @RequestBody final CreateMeetingRequest request,
            final CollaboratifRequestPrincipal principal) {
        MeetingResponse response = meetingService.create(request, principal);
        return ResponseEntity.created(URI.create(LOCATION_PREFIX + response.id())).body(response);
    }
}

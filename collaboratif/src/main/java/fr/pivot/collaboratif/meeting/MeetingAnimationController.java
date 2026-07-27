package fr.pivot.collaboratif.meeting;

import fr.pivot.collaboratif.context.CollaboratifRequestPrincipal;
import fr.pivot.collaboratif.meeting.dto.AddMeetingActionRequest;
import fr.pivot.collaboratif.meeting.dto.MeetingActionDto;
import fr.pivot.collaboratif.meeting.dto.MeetingLiveStateDto;
import fr.pivot.collaboratif.web.CollaboratifApiPaths;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * REST controller exposing MeetOps meeting animation under {@code
 * /collaboratif/meetings/{id}/...} (US12.2.1) — start/agenda-next/end lifecycle, in-meeting
 * action capture, and live-state resynchronisation. Distinct from {@link MeetingController}
 * (US12.1.1, plain {@code /collaboratif/meetings} creation) per this US's own architecture note.
 *
 * <p>Requires a valid {@code Authorization: Bearer <token>} header, resolved into a {@link
 * CollaboratifRequestPrincipal} the same way as every other controller in this module. No
 * business logic lives here — every method is a thin pass-through to {@link
 * MeetingAnimationService}, which owns tenant/authorization resolution via {@link
 * MeetingAccessService}.
 *
 * <p>The full path (including the application context) is {@code
 * /api/collaboratif/meetings/{id}/...}.
 */
@RestController
@RequestMapping(CollaboratifApiPaths.BASE + "/meetings/{id}")
public class MeetingAnimationController {

    private final MeetingAnimationService animationService;

    /**
     * Creates the controller with its required service dependency.
     *
     * @param animationService the meeting animation business logic service
     */
    public MeetingAnimationController(final MeetingAnimationService animationService) {
        this.animationService = animationService;
    }

    /**
     * Starts the meeting (AC-01) — owner or {@code ROLE_ADMIN} only.
     *
     * @param id        the meeting's UUID
     * @param principal the resolved caller identity
     * @return {@code 200} with the resulting live animation state
     */
    @PostMapping("/start")
    public MeetingLiveStateDto start(@PathVariable final UUID id, final CollaboratifRequestPrincipal principal) {
        return animationService.start(id, principal);
    }

    /**
     * Advances to the next agenda item, or ends the meeting if the current item was the last
     * (AC-03) — owner or {@code ROLE_ADMIN} only.
     *
     * @param id        the meeting's UUID
     * @param principal the resolved caller identity
     * @return {@code 200} with the resulting live animation state
     */
    @PostMapping("/agenda/next")
    public MeetingLiveStateDto next(@PathVariable final UUID id, final CollaboratifRequestPrincipal principal) {
        return animationService.next(id, principal);
    }

    /**
     * Ends the meeting (AC-06) — owner or {@code ROLE_ADMIN} only.
     *
     * @param id        the meeting's UUID
     * @param principal the resolved caller identity
     * @return {@code 200} with the resulting live animation state
     */
    @PostMapping("/end")
    public MeetingLiveStateDto end(@PathVariable final UUID id, final CollaboratifRequestPrincipal principal) {
        return animationService.end(id, principal);
    }

    /**
     * Captures a minimal action during the live meeting (AC-08) — owner or {@code ROLE_ADMIN}
     * only.
     *
     * @param id        the meeting's UUID
     * @param request   the action to capture — {@code label}/{@code dueDate} Bean-Validated
     *                  before this method runs (AC-E4)
     * @param principal the resolved caller identity
     * @return HTTP 201 with the created action
     */
    @PostMapping("/actions")
    public ResponseEntity<MeetingActionDto> addAction(
            @PathVariable final UUID id,
            @Valid @RequestBody final AddMeetingActionRequest request,
            final CollaboratifRequestPrincipal principal) {
        MeetingActionDto action = animationService.addAction(id, request, principal);
        return ResponseEntity.status(HttpStatus.CREATED).body(action);
    }

    /**
     * Returns the meeting's full live animation state (AC-07) — any visible participant, not
     * just the animator; used both on initial join and on reconnect resynchronisation.
     *
     * @param id        the meeting's UUID
     * @param principal the resolved caller identity
     * @return the live state, with every timer field freshly computed server-side
     */
    @GetMapping("/live")
    public MeetingLiveStateDto live(@PathVariable final UUID id, final CollaboratifRequestPrincipal principal) {
        return animationService.getLive(id, principal);
    }
}

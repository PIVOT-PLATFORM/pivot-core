package fr.pivot.agilite.capacity;

import fr.pivot.agilite.capacity.dto.AbsenceResponse;
import fr.pivot.agilite.capacity.dto.CreateAbsenceRequest;
import fr.pivot.agilite.capacity.dto.MemberResponse;
import fr.pivot.agilite.capacity.dto.UpdateMemberRequest;
import fr.pivot.agilite.context.RequestPrincipal;
import fr.pivot.agilite.web.AgiliteApiPaths;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * REST controller exposing event roster and absence operations under {@code
 * /capacity/events/{eventId}} (US11.2.1/US11.2.2).
 *
 * <p>The full path (including the application context) is {@code
 * /api/agilite/capacity/events/{eventId}/...}.
 */
@RestController
@RequestMapping(AgiliteApiPaths.BASE + "/capacity/events/{eventId}")
@Validated
public class CapacityMemberController {

    private final CapacityMemberService memberService;
    private final CapacityAbsenceService absenceService;

    /**
     * Creates the controller with its required service dependencies.
     *
     * @param memberService  the roster member business logic service (US11.2.1)
     * @param absenceService the absence business logic service (US11.2.2)
     */
    public CapacityMemberController(final CapacityMemberService memberService, final CapacityAbsenceService absenceService) {
        this.memberService = memberService;
        this.absenceService = absenceService;
    }

    /**
     * Lists an event's roster, each with their absences.
     *
     * @param eventId   the event UUID from the path
     * @param principal the resolved caller identity
     * @return the roster, ordered by name
     */
    @GetMapping("/members")
    public List<MemberResponse> members(@PathVariable final UUID eventId, final RequestPrincipal principal) {
        return memberService.list(eventId, principal.userId(), principal.tenantId());
    }

    /**
     * Adjusts a roster member's availability/exclusion.
     *
     * @param eventId   the event UUID from the path
     * @param memberId  the roster member UUID from the path
     * @param request   the update request
     * @param principal the resolved caller identity
     * @return the updated member response
     */
    @PatchMapping("/members/{memberId}")
    public MemberResponse updateMember(
            @PathVariable final UUID eventId,
            @PathVariable final UUID memberId,
            @RequestBody @Valid final UpdateMemberRequest request,
            final RequestPrincipal principal) {
        return memberService.updateMember(eventId, memberId, request, principal.userId(), principal.tenantId());
    }

    /**
     * Records a new absence for a roster member.
     *
     * @param eventId   the event UUID from the path
     * @param memberId  the roster member UUID from the path
     * @param request   the creation request
     * @param principal the resolved caller identity
     * @return the created absence with HTTP 201 Created
     */
    @PostMapping("/members/{memberId}/absences")
    @ResponseStatus(HttpStatus.CREATED)
    public AbsenceResponse createAbsence(
            @PathVariable final UUID eventId,
            @PathVariable final UUID memberId,
            @RequestBody @Valid final CreateAbsenceRequest request,
            final RequestPrincipal principal) {
        return absenceService.create(eventId, memberId, request, principal.userId(), principal.tenantId());
    }

    /**
     * Deletes an absence.
     *
     * @param eventId   the event UUID from the path
     * @param absenceId the absence UUID from the path
     * @param principal the resolved caller identity
     */
    @DeleteMapping("/absences/{absenceId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteAbsence(
            @PathVariable final UUID eventId, @PathVariable final UUID absenceId, final RequestPrincipal principal) {
        absenceService.delete(eventId, absenceId, principal.userId(), principal.tenantId());
    }
}

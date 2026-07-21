package fr.pivot.agilite.capacity;

import fr.pivot.agilite.capacity.dto.CapacityAbsenceRequest;
import fr.pivot.agilite.capacity.dto.CapacityAbsenceResponse;
import fr.pivot.agilite.capacity.dto.CapacityMemberRequest;
import fr.pivot.agilite.capacity.dto.CapacityMemberResponse;
import fr.pivot.agilite.context.RequestPrincipal;
import fr.pivot.agilite.web.AgiliteApiPaths;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * REST controller exposing capacity event members and their absences (E11, F11.2).
 *
 * <p>Full path (including the application context) is {@code /api/agilite/capacity}. Every
 * endpoint requires a valid {@code Authorization: Bearer <token>} header, resolved into a {@link
 * RequestPrincipal} by {@code RequestPrincipalResolver} (EN08.3).
 */
@RestController
@RequestMapping(AgiliteApiPaths.BASE + "/capacity")
@Validated
public class CapacityMemberController {

    private final CapacityMemberService memberService;

    /**
     * Creates the controller with its required service dependency.
     *
     * @param memberService the capacity member/absence business logic service
     */
    public CapacityMemberController(final CapacityMemberService memberService) {
        this.memberService = memberService;
    }

    /**
     * Adds a member to a capacity event.
     *
     * @param eventId   the owning event's identifier
     * @param request   the member's data
     * @param principal the resolved caller identity
     * @return the created member, HTTP 201
     */
    @PostMapping("/events/{eventId}/members")
    @ResponseStatus(HttpStatus.CREATED)
    public CapacityMemberResponse addMember(
            @PathVariable final UUID eventId,
            @RequestBody @Valid final CapacityMemberRequest request,
            final RequestPrincipal principal) {
        return memberService.addMember(eventId, request, principal.userId(), principal.tenantId());
    }

    /**
     * Updates an existing capacity event member.
     *
     * @param memberId  the member's identifier
     * @param request   the member's new data
     * @param principal the resolved caller identity
     * @return the updated member
     */
    @PutMapping("/members/{memberId}")
    public CapacityMemberResponse updateMember(
            @PathVariable final UUID memberId,
            @RequestBody @Valid final CapacityMemberRequest request,
            final RequestPrincipal principal) {
        return memberService.updateMember(memberId, request, principal.userId(), principal.tenantId());
    }

    /**
     * Removes a member from its event, cascading its absences.
     *
     * @param memberId  the member's identifier
     * @param principal the resolved caller identity
     */
    @DeleteMapping("/members/{memberId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteMember(
            @PathVariable final UUID memberId,
            final RequestPrincipal principal) {
        memberService.deleteMember(memberId, principal.userId(), principal.tenantId());
    }

    /**
     * Adds an absence to a capacity event member.
     *
     * @param memberId  the owning member's identifier
     * @param request   the absence's data
     * @param principal the resolved caller identity
     * @return the created absence, HTTP 201
     */
    @PostMapping("/members/{memberId}/absences")
    @ResponseStatus(HttpStatus.CREATED)
    public CapacityAbsenceResponse addAbsence(
            @PathVariable final UUID memberId,
            @RequestBody @Valid final CapacityAbsenceRequest request,
            final RequestPrincipal principal) {
        return memberService.addAbsence(memberId, request, principal.userId(), principal.tenantId());
    }

    /**
     * Removes an absence.
     *
     * @param absenceId the absence's identifier
     * @param principal the resolved caller identity
     */
    @DeleteMapping("/absences/{absenceId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteAbsence(
            @PathVariable final UUID absenceId,
            final RequestPrincipal principal) {
        memberService.deleteAbsence(absenceId, principal.userId(), principal.tenantId());
    }
}

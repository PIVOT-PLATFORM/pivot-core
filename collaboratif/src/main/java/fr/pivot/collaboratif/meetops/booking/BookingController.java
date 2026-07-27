package fr.pivot.collaboratif.meetops.booking;

import fr.pivot.collaboratif.context.CollaboratifRequestPrincipal;
import fr.pivot.collaboratif.meetops.booking.dto.AdjustSlotRequest;
import fr.pivot.collaboratif.meetops.booking.dto.ConfirmSlotRequest;
import fr.pivot.collaboratif.meetops.booking.dto.MeetingBookingResponse;
import fr.pivot.collaboratif.web.CollaboratifApiPaths;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * REST controller exposing the MeetOps roadmap booking flow under {@code
 * /collaboratif/meetings/{id}} (US12.4.1) — shares its base path with {@code MeetingController}
 * (US12.1.1), which only ever maps bare {@code POST .../meetings}, so no route collides.
 *
 * <p>Requires a valid {@code Authorization: Bearer <token>} header, resolved into a {@link
 * CollaboratifRequestPrincipal} (EN08.3). Tenant and user identity always come from the resolved
 * principal — never from the path/body (tenant isolation, anti-IDOR). No business logic lives
 * here — every check (tenant isolation → 404, organizer-only → 403, state conflicts → 409/422) is
 * delegated to {@link BookingService}.
 */
@RestController
@RequestMapping(CollaboratifApiPaths.BASE + "/meetings")
public class BookingController {

    private final BookingService bookingService;

    /**
     * Creates the controller with its required service dependency.
     *
     * @param bookingService the booking business logic service
     */
    public BookingController(final BookingService bookingService) {
        this.bookingService = bookingService;
    }

    /**
     * Returns a meeting's current booking state — status, booking window, and ranked proposed
     * slots (US12.4.1).
     *
     * @param meetingId the meeting id
     * @param principal the resolved caller identity
     * @return the meeting's booking state
     */
    @GetMapping("/{meetingId}")
    public MeetingBookingResponse getById(
            @PathVariable final UUID meetingId, final CollaboratifRequestPrincipal principal) {
        return bookingService.getById(meetingId, principal);
    }

    /**
     * Confirms a proposed (or manually adjusted) slot — organizer-only (US12.4.1 AC "Confirmation
     * → CONFIRMED + bus").
     *
     * @param meetingId the meeting id
     * @param request   the retained slot id
     * @param principal the resolved caller identity — must be the meeting's organizer
     * @return the confirmed meeting
     */
    @PostMapping("/{meetingId}/confirm")
    public MeetingBookingResponse confirm(
            @PathVariable final UUID meetingId,
            @Valid @RequestBody final ConfirmSlotRequest request,
            final CollaboratifRequestPrincipal principal) {
        return bookingService.confirm(meetingId, request.slotId(), principal);
    }

    /**
     * Manually adjusts a proposed slot's boundaries while the meeting is still pre-reserved —
     * organizer-only (US12.4.1 AC "Validation humaine").
     *
     * @param meetingId the meeting id
     * @param request   the slot id plus its new boundaries
     * @param principal the resolved caller identity — must be the meeting's organizer
     * @return the meeting, still pre-reserved, with the adjusted slot
     */
    @PatchMapping("/{meetingId}/slot")
    public MeetingBookingResponse adjustSlot(
            @PathVariable final UUID meetingId,
            @Valid @RequestBody final AdjustSlotRequest request,
            final CollaboratifRequestPrincipal principal) {
        return bookingService.adjustSlot(meetingId, request.slotId(), request.start(), request.end(), principal);
    }
}

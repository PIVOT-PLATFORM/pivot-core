package fr.pivot.agilite.standup;

import fr.pivot.agilite.context.RequestPrincipal;
import fr.pivot.agilite.standup.dto.CreateStandupSessionRequest;
import fr.pivot.agilite.standup.dto.ExtendTimerRequest;
import fr.pivot.agilite.standup.dto.ReorderParticipantsRequest;
import fr.pivot.agilite.standup.dto.StandupSessionResponse;
import fr.pivot.agilite.web.AgiliteApiPaths;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * REST controller exposing daily standup session lifecycle and animation-control operations
 * under {@code /standup/sessions} (US10.1.1/US10.1.2/US10.2.2).
 *
 * <p>All endpoints require a valid {@code Authorization: Bearer <token>} header, resolved into a
 * {@link RequestPrincipal} by {@link fr.pivot.agilite.context.RequestPrincipalResolver}. Missing,
 * malformed, or rejected tokens result in HTTP 401.
 *
 * <p>The full path (including the application context) is
 * {@code /api/agilite/standup/sessions}.
 */
@RestController
@RequestMapping(AgiliteApiPaths.BASE + "/standup/sessions")
@Validated
public class StandupSessionController {

    private final StandupSessionService sessionService;

    /**
     * Creates the controller with its required service dependency.
     *
     * @param sessionService the standup session business logic service
     */
    public StandupSessionController(final StandupSessionService sessionService) {
        this.sessionService = sessionService;
    }

    /**
     * Creates a new standup session with its participants.
     *
     * @param request   the creation request
     * @param principal the resolved caller identity (user + tenant)
     * @return the created session with HTTP 201 Created
     */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public StandupSessionResponse create(
            @RequestBody @Valid final CreateStandupSessionRequest request,
            final RequestPrincipal principal) {
        return sessionService.create(
                request.teamId(), request.name(), request.timePerPersonSeconds(),
                request.participantTeamMemberIds(), principal.userId(), principal.tenantId());
    }

    /**
     * Lists sessions accessible to the caller, optionally filtered by team and/or status.
     *
     * @param teamId    an explicit team to scope the listing to, or {@code null} for every team
     *                  the caller belongs to
     * @param status    an explicit status filter (e.g. {@code PENDING}), or {@code null}
     * @param principal the resolved caller identity
     * @return the matching sessions, {@code createdAt} descending
     */
    @GetMapping
    public List<StandupSessionResponse> list(
            @RequestParam(required = false) final Long teamId,
            @RequestParam(required = false) final StandupSessionStatus status,
            final RequestPrincipal principal) {
        return sessionService.list(teamId, status, principal.userId(), principal.tenantId());
    }

    /**
     * Returns a single session by its identifier, if the caller has access.
     *
     * @param sessionId the session UUID from the path
     * @param principal the resolved caller identity
     * @return the session, or HTTP 404 if not found or inaccessible
     */
    @GetMapping("/{sessionId}")
    public StandupSessionResponse findById(
            @PathVariable final UUID sessionId,
            final RequestPrincipal principal) {
        return sessionService.getById(sessionId, principal.userId(), principal.tenantId());
    }

    /**
     * Permanently deletes a {@code PENDING} or {@code DONE} session and its participants.
     *
     * @param sessionId the session UUID from the path
     * @param principal the resolved caller identity
     */
    @DeleteMapping("/{sessionId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(
            @PathVariable final UUID sessionId,
            final RequestPrincipal principal) {
        sessionService.delete(sessionId, principal.userId(), principal.tenantId());
    }

    /**
     * Starts a {@code PENDING} session.
     *
     * @param sessionId the session UUID from the path
     * @param principal the resolved caller identity
     * @return the started session
     */
    @PostMapping("/{sessionId}/start")
    public StandupSessionResponse start(
            @PathVariable final UUID sessionId,
            final RequestPrincipal principal) {
        return sessionService.start(sessionId, principal.userId(), principal.tenantId());
    }

    /**
     * Rotates the speaking turn to the next {@code WAITING} participant, or ends the session.
     *
     * @param sessionId the session UUID from the path
     * @param principal the resolved caller identity
     * @return the session's current state after the rotation (or idempotent no-op)
     */
    @PostMapping("/{sessionId}/next")
    public StandupSessionResponse next(
            @PathVariable final UUID sessionId,
            final RequestPrincipal principal) {
        return sessionService.next(sessionId, principal.userId(), principal.tenantId());
    }

    /**
     * Skips the current speaker, rotating to the next {@code WAITING} participant, or ending the
     * session (US10.2.2).
     *
     * @param sessionId the session UUID from the path
     * @param principal the resolved caller identity
     * @return the session's current state after the skip (or idempotent no-op)
     */
    @PostMapping("/{sessionId}/skip")
    public StandupSessionResponse skip(
            @PathVariable final UUID sessionId,
            final RequestPrincipal principal) {
        return sessionService.skip(sessionId, principal.userId(), principal.tenantId());
    }

    /**
     * Ends a {@code RUNNING} session early.
     *
     * @param sessionId the session UUID from the path
     * @param principal the resolved caller identity
     * @return the ended session
     */
    @PostMapping("/{sessionId}/end")
    public StandupSessionResponse end(
            @PathVariable final UUID sessionId,
            final RequestPrincipal principal) {
        return sessionService.end(sessionId, principal.userId(), principal.tenantId());
    }

    /**
     * Extends the current speaker's time (US10.2.2).
     *
     * @param sessionId the session UUID from the path
     * @param request   the extension request — {@code seconds}, must be {@code 30} or {@code 60}
     * @param principal the resolved caller identity
     * @return the session's current state after the extension
     */
    @PostMapping("/{sessionId}/extend")
    public StandupSessionResponse extend(
            @PathVariable final UUID sessionId,
            @RequestBody @Valid final ExtendTimerRequest request,
            final RequestPrincipal principal) {
        return sessionService.extend(sessionId, request.seconds(), principal.userId(), principal.tenantId());
    }

    /**
     * Reorders the still-{@code WAITING} tail of the speaking queue (US10.2.2).
     *
     * @param sessionId the session UUID from the path
     * @param request   the reorder request — the new order for the {@code WAITING} participants
     * @param principal the resolved caller identity
     * @return the session's current state after the reorder
     */
    @PutMapping("/{sessionId}/participants/reorder")
    public StandupSessionResponse reorder(
            @PathVariable final UUID sessionId,
            @RequestBody @Valid final ReorderParticipantsRequest request,
            final RequestPrincipal principal) {
        return sessionService.reorder(
                sessionId, request.participantIds(), principal.userId(), principal.tenantId());
    }
}

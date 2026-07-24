package fr.pivot.collaboratif.exception;

import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

/**
 * Global exception handler that maps domain exceptions to RFC 7807 Problem Detail responses.
 *
 * <p><strong>Renamed from {@code GlobalExceptionHandler}, and scoped with {@code basePackages}
 * (EN53.2 Vague 2 modulith merge)</strong> — mirrors {@code
 * fr.pivot.agilite.exception.AgiliteExceptionHandler}'s identical EN53.1 Vague 1 fix. Two
 * problems, both caused by aggregating this module into {@code pivot-core-app}'s single Spring
 * context alongside the shell and the agilite module:
 * <ol>
 *   <li><strong>Bean name collision.</strong> Spring's default annotation-based bean-name
 *       generator derives a bean id from the simple class name (decapitalized). A generic name
 *       like {@code GlobalExceptionHandler} is exactly the kind of name another module's own
 *       exception handler is likely to reuse — renaming to a module-qualified name removes any
 *       ambiguity.</li>
 *   <li><strong>Unscoped {@code @RestControllerAdvice} applies globally.</strong> Without {@code
 *       basePackages}, a {@code @RestControllerAdvice} bean applies to <em>every</em> {@code
 *       @RestController} in the aggregated context, not just this module's own — including the
 *       shell's and agilite's controllers. Its {@link #handleValidation}/{@link
 *       #handleConstraintViolation}/{@link #handleIllegalArgument} generic handlers
 *       (Bean Validation, constraint violations, illegal arguments) would otherwise silently
 *       intercept validation failures on shell/agilite endpoints too, competing with — or
 *       shadowing, depending on advice ordering — their own equivalent handlers. Scoping to
 *       {@code basePackages = "fr.pivot.collaboratif"} confines every handler in this class to
 *       controllers in this module's own package tree only.</li>
 * </ol>
 *
 * <p>Handles board-specific domain exceptions ({@link BoardNotFoundException},
 * {@link BoardAccessDeniedException}, {@link WhiteboardModuleDisabledException},
 * {@link BoardShareTokenNotFoundException}, {@link BoardShareTokenExpiredException},
 * {@link BoardMemberNotFoundException}, {@link BoardNotInTrashException},
 * {@link InvalidActivityException}, {@link TooManyRequestsException}), invitation-by-email
 * domain exceptions ({@link InviteeNotFoundException}, {@link InvalidInvitationException},
 * US08.2.5), template domain exceptions ({@link TemplateNotFoundException},
 * {@link InvalidTemplateIdException}, {@link InvalidCanvasElementException}), as well as
 * Spring MVC validation failures ({@link MethodArgumentNotValidException}).
 */
@RestControllerAdvice(basePackages = "fr.pivot.collaboratif")
public class CollaboratifExceptionHandler {

    private static final Logger LOG = LoggerFactory.getLogger(CollaboratifExceptionHandler.class);

    /**
     * Returns HTTP 404 when a board is not found, belongs to another tenant,
     * or the caller is not a member.
     *
     * @param ex the thrown exception
     * @return a 404 problem detail
     */
    @ExceptionHandler(BoardNotFoundException.class)
    public ProblemDetail handleBoardNotFound(final BoardNotFoundException ex) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.NOT_FOUND);
        problem.setTitle("Board not found");
        problem.setDetail(ex.getMessage());
        return problem;
    }

    /**
     * Returns HTTP 403 when the caller is a member but lacks the required role.
     *
     * @param ex the thrown exception
     * @return a 403 problem detail
     */
    @ExceptionHandler(BoardAccessDeniedException.class)
    public ProblemDetail handleBoardAccessDenied(final BoardAccessDeniedException ex) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.FORBIDDEN);
        problem.setTitle("Access denied");
        problem.setDetail(ex.getMessage());
        return problem;
    }

    /**
     * Returns HTTP 403 when the whiteboard module is disabled for the caller's tenant.
     *
     * @param ex the thrown exception
     * @return a 403 problem detail
     */
    @ExceptionHandler(WhiteboardModuleDisabledException.class)
    public ProblemDetail handleModuleDisabled(final WhiteboardModuleDisabledException ex) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.FORBIDDEN);
        problem.setTitle("Module disabled");
        problem.setDetail(ex.getMessage());
        return problem;
    }

    /**
     * Returns HTTP 404 when a share token does not exist, belongs to a different board,
     * or has already been revoked.
     *
     * @param ex the thrown exception
     * @return a 404 problem detail
     */
    @ExceptionHandler(BoardShareTokenNotFoundException.class)
    public ProblemDetail handleShareTokenNotFound(final BoardShareTokenNotFoundException ex) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.NOT_FOUND);
        problem.setTitle("Share token not found");
        problem.setDetail(ex.getMessage());
        return problem;
    }

    /**
     * Returns HTTP 410 Gone when a share token has expired or exhausted its use count.
     *
     * @param ex the thrown exception
     * @return a 410 problem detail
     */
    @ExceptionHandler(BoardShareTokenExpiredException.class)
    public ProblemDetail handleShareTokenExpired(final BoardShareTokenExpiredException ex) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.GONE);
        problem.setTitle("Share token expired");
        problem.setDetail(ex.getMessage());
        return problem;
    }

    /**
     * Returns HTTP 404 when a board membership record does not exist.
     *
     * @param ex the thrown exception
     * @return a 404 problem detail
     */
    @ExceptionHandler(BoardMemberNotFoundException.class)
    public ProblemDetail handleMemberNotFound(final BoardMemberNotFoundException ex) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.NOT_FOUND);
        problem.setTitle("Member not found");
        problem.setDetail(ex.getMessage());
        return problem;
    }

    /**
     * Returns HTTP 404 when an invitation targets an e-mail that resolves to no active user of the
     * caller's tenant (US08.2.5) — deliberately indistinguishable from an unknown board/share so
     * an e-mail from another tenant cannot be enumerated.
     *
     * @param ex the thrown exception
     * @return a 404 problem detail
     */
    @ExceptionHandler(InviteeNotFoundException.class)
    public ProblemDetail handleInviteeNotFound(final InviteeNotFoundException ex) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.NOT_FOUND);
        problem.setTitle("Invitee not found");
        problem.setDetail(ex.getMessage());
        problem.setProperties(Map.of("code", "INVITEE_NOT_FOUND"));
        return problem;
    }

    /**
     * Returns HTTP 400 with a machine-readable {@code code} property when an invitation is
     * disallowed for a business reason (US08.2.5): {@code SELF_INVITE}.
     *
     * @param ex the thrown exception
     * @return a 400 problem detail carrying the code
     */
    @ExceptionHandler(InvalidInvitationException.class)
    public ProblemDetail handleInvalidInvitation(final InvalidInvitationException ex) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.BAD_REQUEST);
        problem.setTitle("Invalid invitation");
        problem.setDetail(ex.getMessage());
        problem.setProperties(Map.of("code", ex.getCode()));
        return problem;
    }

    /**
     * Returns HTTP 409 Conflict when a restore or permanent-delete operation targets a board
     * that is not currently in the trash.
     *
     * @param ex the thrown exception
     * @return a 409 problem detail
     */
    @ExceptionHandler(BoardNotInTrashException.class)
    public ProblemDetail handleBoardNotInTrash(final BoardNotInTrashException ex) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.CONFLICT);
        problem.setTitle("Board not in trash");
        problem.setDetail(ex.getMessage());
        return problem;
    }

    /**
     * Returns HTTP 400 with a machine-readable {@code code} property when
     * {@code enabledActivities} contains an unknown activity code.
     *
     * @param ex the thrown exception
     * @return a 400 problem detail with {@code { "code": "INVALID_ACTIVITY" } }
     */
    @ExceptionHandler(InvalidActivityException.class)
    public ProblemDetail handleInvalidActivity(final InvalidActivityException ex) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.BAD_REQUEST);
        problem.setTitle("Invalid activity");
        problem.setDetail(ex.getMessage());
        problem.setProperties(Map.of("code", "INVALID_ACTIVITY"));
        return problem;
    }

    /**
     * Returns HTTP 429 Too Many Requests when the rate limit is exceeded.
     *
     * @param ex the thrown exception
     * @return a 429 problem detail
     */
    @ExceptionHandler(TooManyRequestsException.class)
    public ProblemDetail handleTooManyRequests(final TooManyRequestsException ex) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.TOO_MANY_REQUESTS);
        problem.setTitle("Too many requests");
        problem.setDetail(ex.getMessage());
        return problem;
    }

    /**
     * Returns HTTP 404 when a {@code templateId} does not resolve to an existing global
     * whiteboard template.
     *
     * @param ex the thrown exception
     * @return a 404 problem detail
     */
    @ExceptionHandler(TemplateNotFoundException.class)
    public ProblemDetail handleTemplateNotFound(final TemplateNotFoundException ex) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.NOT_FOUND);
        problem.setTitle("Template not found");
        problem.setDetail(ex.getMessage());
        return problem;
    }

    /**
     * Returns HTTP 400 with a machine-readable {@code code} property when the
     * {@code templateId} query parameter is not a syntactically valid UUID.
     *
     * @param ex the thrown exception
     * @return a 400 problem detail with {@code { "code": "INVALID_TEMPLATE_ID" } }
     */
    @ExceptionHandler(InvalidTemplateIdException.class)
    public ProblemDetail handleInvalidTemplateId(final InvalidTemplateIdException ex) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.BAD_REQUEST);
        problem.setTitle("Invalid template id");
        problem.setProperties(Map.of("code", "INVALID_TEMPLATE_ID"));
        return problem;
    }

    /**
     * Returns HTTP 500 when whiteboard template seed content fails the strict
     * shape/text/image JSON schema whitelist at board-initialization time.
     *
     * <p>This is always an internal invariant violation (seed data drift), never a
     * caller input error — the underlying diagnostic detail is logged server-side only
     * and not echoed back in the response body.
     *
     * @param ex the thrown exception
     * @return a generic 500 problem detail
     */
    @ExceptionHandler(InvalidCanvasElementException.class)
    public ProblemDetail handleInvalidCanvasElement(final InvalidCanvasElementException ex) {
        LOG.error("Invalid canvas element content (seed/template data schema drift): {}",
                ex.getMessage());
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.INTERNAL_SERVER_ERROR);
        problem.setTitle("Internal error");
        problem.setDetail("Unable to initialize board content");
        return problem;
    }

    /**
     * Returns HTTP 400 with a machine-readable {@code code} property for board title
     * validation failures.
     *
     * <p>The {@code code} value is extracted from the first field error's default message,
     * which is set to {@code "INVALID_TITLE"} by the validation constraints on
     * {@code CreateBoardRequest} and {@code RenameBoardRequest}.
     *
     * @param ex the validation exception
     * @return a 400 problem detail with a {@code code} property
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ProblemDetail handleValidation(final MethodArgumentNotValidException ex) {
        String firstMessage = ex.getBindingResult()
                .getFieldErrors()
                .stream()
                .findFirst()
                .map(fe -> fe.getDefaultMessage())
                .orElse("VALIDATION_ERROR");
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.BAD_REQUEST);
        problem.setTitle("Validation failed");
        problem.setProperties(Map.of("code", firstMessage));
        return problem;
    }

    /**
     * Returns HTTP 400 for parameter constraint violations (e.g. {@code @Min} / {@code @Max}
     * on request parameters).
     *
     * @param ex the constraint violation exception
     * @return a 400 problem detail
     */
    @ExceptionHandler(ConstraintViolationException.class)
    public ProblemDetail handleConstraintViolation(final ConstraintViolationException ex) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.BAD_REQUEST);
        problem.setTitle("Validation failed");
        problem.setDetail(ex.getMessage());
        return problem;
    }

    /**
     * Returns HTTP 400 for invalid argument values (defensive service-layer guard).
     *
     * @param ex the illegal argument exception
     * @return a 400 problem detail
     */
    @ExceptionHandler(IllegalArgumentException.class)
    public ProblemDetail handleIllegalArgument(final IllegalArgumentException ex) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.BAD_REQUEST);
        problem.setTitle("Bad request");
        problem.setDetail(ex.getMessage());
        return problem;
    }

    /**
     * Returns HTTP 404 when a session id does not resolve to a session accessible to the caller
     * (E19 — never 403, anti-enumeration).
     *
     * @param ex the thrown exception
     * @return a 404 problem detail
     */
    @ExceptionHandler(SessionNotFoundException.class)
    public ProblemDetail handleSessionNotFound(final SessionNotFoundException ex) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.NOT_FOUND);
        problem.setTitle("Session not found");
        problem.setDetail(ex.getMessage());
        return problem;
    }

    /**
     * Returns HTTP 409 with code {@code INVALID_SESSION_TRANSITION} when a session lifecycle
     * transition is attempted from a status that does not allow it (US19.1.2).
     *
     * @param ex the thrown exception
     * @return a 409 problem detail carrying the code
     */
    @ExceptionHandler(InvalidSessionTransitionException.class)
    public ProblemDetail handleInvalidSessionTransition(final InvalidSessionTransitionException ex) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.CONFLICT);
        problem.setTitle("Invalid session transition");
        problem.setDetail(ex.getMessage());
        problem.setProperties(Map.of("code", "INVALID_SESSION_TRANSITION"));
        return problem;
    }

    /**
     * Returns HTTP 409 with code {@code INVALID_SESSION_STATUS} when an activity-specific action
     * is attempted on a session of the wrong type or not currently LIVE (US19.3.2/US19.3.3).
     *
     * @param ex the thrown exception
     * @return a 409 problem detail carrying the code
     */
    @ExceptionHandler(InvalidSessionStatusException.class)
    public ProblemDetail handleInvalidSessionStatus(final InvalidSessionStatusException ex) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.CONFLICT);
        problem.setTitle("Invalid session status");
        problem.setDetail(ex.getMessage());
        problem.setProperties(Map.of("code", "INVALID_SESSION_STATUS"));
        return problem;
    }

    /**
     * Returns HTTP 401 with code {@code GUEST_SESSION_EXPIRED} when a guest token does not
     * resolve to a currently valid guest session (US19.2.1).
     *
     * @param ex the thrown exception
     * @return a 401 problem detail carrying the code
     */
    @ExceptionHandler(SessionGuestExpiredException.class)
    public ProblemDetail handleGuestExpired(final SessionGuestExpiredException ex) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.UNAUTHORIZED);
        problem.setTitle("Guest session expired");
        problem.setDetail(ex.getMessage());
        problem.setProperties(Map.of("code", "GUEST_SESSION_EXPIRED"));
        return problem;
    }

    /**
     * Returns HTTP 400 with a machine-readable {@code code} property for session-domain
     * business-rule validation failures that are not expressible as a Bean Validation constraint.
     *
     * @param ex the thrown exception
     * @return a 400 problem detail carrying the code
     */
    @ExceptionHandler(SessionValidationException.class)
    public ProblemDetail handleSessionValidation(final SessionValidationException ex) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.BAD_REQUEST);
        problem.setTitle("Invalid request");
        problem.setDetail(ex.getMessage());
        problem.setProperties(Map.of("code", ex.getCode()));
        return problem;
    }

    /**
     * Returns HTTP 409 with a machine-readable {@code code} property for session-domain
     * business-rule conflicts.
     *
     * @param ex the thrown exception
     * @return a 409 problem detail carrying the code
     */
    @ExceptionHandler(SessionConflictException.class)
    public ProblemDetail handleSessionConflict(final SessionConflictException ex) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.CONFLICT);
        problem.setTitle("Conflict");
        problem.setDetail(ex.getMessage());
        problem.setProperties(Map.of("code", ex.getCode()));
        return problem;
    }
}

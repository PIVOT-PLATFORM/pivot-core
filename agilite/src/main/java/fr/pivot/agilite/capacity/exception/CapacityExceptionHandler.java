package fr.pivot.agilite.capacity.exception;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

/**
 * Exception handler mapping E11 capacity-planning domain exceptions to RFC 7807 Problem Detail
 * responses, scoped to the {@code fr.pivot.agilite.capacity} controllers.
 *
 * <p>It intentionally handles <strong>only</strong> capacity-specific domain exceptions
 * ({@link CapacityEventNotFoundException}, {@link CapacityEventMemberNotFoundException}, {@link
 * CapacityAbsenceNotFoundException}, {@link CapacityAccessDeniedException}, {@link
 * CapacityValidationException}). Generic Spring MVC / Bean Validation failures
 * ({@code MethodArgumentNotValidException}, {@code ConstraintViolationException}) are left to the
 * module-wide {@code AgiliteExceptionHandler} — no exception type is handled by both advices, so
 * there is no ambiguity for a capacity controller even though both advices apply to it.
 */
@RestControllerAdvice(basePackages = "fr.pivot.agilite.capacity")
public class CapacityExceptionHandler {

    /**
     * Returns HTTP 404 when a capacity event is not found, or belongs to another tenant, or the
     * caller is not a member of its team — all deliberately indistinguishable to avoid confirming
     * cross-tenant existence.
     *
     * @param ex the thrown exception
     * @return a 404 problem detail
     */
    @ExceptionHandler(CapacityEventNotFoundException.class)
    public ProblemDetail handleEventNotFound(final CapacityEventNotFoundException ex) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.NOT_FOUND);
        problem.setTitle("Capacity event not found");
        problem.setDetail(ex.getMessage());
        return problem;
    }

    /**
     * Returns HTTP 404 when a capacity event member is not found, or belongs to an event owned by
     * another tenant — deliberately indistinguishable to avoid confirming cross-tenant existence.
     *
     * @param ex the thrown exception
     * @return a 404 problem detail
     */
    @ExceptionHandler(CapacityEventMemberNotFoundException.class)
    public ProblemDetail handleMemberNotFound(final CapacityEventMemberNotFoundException ex) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.NOT_FOUND);
        problem.setTitle("Capacity event member not found");
        problem.setDetail(ex.getMessage());
        return problem;
    }

    /**
     * Returns HTTP 404 when a capacity absence is not found, or belongs to an event owned by
     * another tenant — deliberately indistinguishable to avoid confirming cross-tenant existence.
     *
     * @param ex the thrown exception
     * @return a 404 problem detail
     */
    @ExceptionHandler(CapacityAbsenceNotFoundException.class)
    public ProblemDetail handleAbsenceNotFound(final CapacityAbsenceNotFoundException ex) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.NOT_FOUND);
        problem.setTitle("Capacity absence not found");
        problem.setDetail(ex.getMessage());
        return problem;
    }

    /**
     * Returns HTTP 403 when the caller is a member of the event's team but lacks the role
     * required for the attempted write operation (OWNER/EDITOR/VIEWER enforced in service).
     *
     * @param ex the thrown exception
     * @return a 403 problem detail
     */
    @ExceptionHandler(CapacityAccessDeniedException.class)
    public ProblemDetail handleAccessDenied(final CapacityAccessDeniedException ex) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.FORBIDDEN);
        problem.setTitle("Capacity access denied");
        problem.setDetail(ex.getMessage());
        return problem;
    }

    /**
     * Returns HTTP 400 with a machine-readable {@code code} property when a capacity request
     * violates a domain rule (invalid date range, focus out of range, hierarchy too deep, etc.).
     *
     * @param ex the thrown exception
     * @return a 400 problem detail carrying {@code { "code": <ex.getCode()> }}
     */
    @ExceptionHandler(CapacityValidationException.class)
    public ProblemDetail handleValidation(final CapacityValidationException ex) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.BAD_REQUEST);
        problem.setTitle("Capacity validation error");
        problem.setDetail(ex.getMessage());
        problem.setProperties(Map.of("code", ex.getCode()));
        return problem;
    }
}

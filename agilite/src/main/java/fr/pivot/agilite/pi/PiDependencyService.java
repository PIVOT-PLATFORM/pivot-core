package fr.pivot.agilite.pi;

import fr.pivot.agilite.exception.PiNotFoundException;
import fr.pivot.agilite.exception.PiValidationException;
import fr.pivot.agilite.pi.dto.CreateDependencyRequest;
import fr.pivot.agilite.pi.dto.DependencyResponse;
import fr.pivot.agilite.pi.dto.UpdateDependencyRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * Business logic for Program Board dependency operations (US50.3.2).
 *
 * <p>All read operations are wrapped in a read-only transaction; write operations use a full
 * read-write transaction. Cycle access is enforced via {@link PiCycleAccessService}. Cycle
 * detection is delegated to the pure, persistence-free {@link PiDependencyCycleDetector}.
 */
@Service
@Transactional(readOnly = true)
public class PiDependencyService {

    private final PiCycleAccessService cycleAccessService;
    private final PiTicketRepository ticketRepository;
    private final PiDependencyRepository dependencyRepository;

    /**
     * Creates the service with all required dependencies.
     *
     * @param cycleAccessService   shared cycle-resolution/access-check helper
     * @param ticketRepository     repository for ticket persistence, used to validate the two
     *                             referenced tickets belong to the same cycle
     * @param dependencyRepository repository for dependency persistence
     */
    public PiDependencyService(
            final PiCycleAccessService cycleAccessService,
            final PiTicketRepository ticketRepository,
            final PiDependencyRepository dependencyRepository) {
        this.cycleAccessService = cycleAccessService;
        this.ticketRepository = ticketRepository;
        this.dependencyRepository = dependencyRepository;
    }

    /**
     * Creates a new dependency between two tickets of the same cycle, rejecting a self-dependency,
     * a cross-cycle ticket reference, a duplicate pair, or an edge that would create a cycle.
     *
     * @param cycleId      the cycle UUID
     * @param request      the creation request
     * @param callerUserId the calling user's {@code public.users.id}
     * @param tenantId     the calling tenant's {@code public.tenants.id}
     * @return the created dependency response
     * @throws PiNotFoundException if the cycle does not exist, belongs to another tenant, or the
     *     caller has no access to it
     * @throws PiValidationException {@code SELF_DEPENDENCY}, {@code INVALID_TICKET}, {@code
     *     DUPLICATE_DEPENDENCY}, or {@code DEPENDENCY_CYCLE} depending on the rejection reason
     */
    @Transactional
    public DependencyResponse create(
            final UUID cycleId, final CreateDependencyRequest request, final Long callerUserId, final Long tenantId) {
        PiCycle cycle = cycleAccessService.resolveCycleForCaller(cycleId, callerUserId, tenantId);
        UUID fromId = request.fromTicketId();
        UUID toId = request.toTicketId();
        if (fromId.equals(toId)) {
            throw new PiValidationException("SELF_DEPENDENCY", "A ticket cannot depend on itself");
        }
        ticketRepository.findByIdAndCycleId(fromId, cycle.getId())
                .orElseThrow(() -> new PiValidationException("INVALID_TICKET", "fromTicketId does not belong to this cycle"));
        ticketRepository.findByIdAndCycleId(toId, cycle.getId())
                .orElseThrow(() -> new PiValidationException("INVALID_TICKET", "toTicketId does not belong to this cycle"));
        if (dependencyRepository.existsByFromTicketIdAndToTicketId(fromId, toId)) {
            throw new PiValidationException("DUPLICATE_DEPENDENCY", "This dependency already exists");
        }
        List<PiDependencyCycleDetector.Edge> edges = dependencyRepository.findAllByCycleId(cycle.getId()).stream()
                .map(dependency -> new PiDependencyCycleDetector.Edge(
                        dependency.getFromTicketId(), dependency.getToTicketId()))
                .toList();
        if (PiDependencyCycleDetector.wouldCreateCycle(edges, fromId, toId)) {
            throw new PiValidationException("DEPENDENCY_CYCLE", "This dependency would create a cycle");
        }
        PiDependencyStatus status = request.status() != null ? request.status() : PiDependencyStatus.OK;
        PiDependency dependency = new PiDependency(cycle.getId(), fromId, toId, status, normalize(request.note()));
        return DependencyResponse.from(dependencyRepository.save(dependency));
    }

    /**
     * Updates a dependency's status/note — only non-{@code null} request fields are applied.
     *
     * @param cycleId      the cycle UUID
     * @param dependencyId the dependency UUID
     * @param request      the update request
     * @param callerUserId the calling user's {@code public.users.id}
     * @param tenantId     the calling tenant's {@code public.tenants.id}
     * @return the updated dependency response
     * @throws PiNotFoundException if the cycle/dependency does not exist, belongs to another
     *     tenant, or the caller has no access to it
     */
    @Transactional
    public DependencyResponse update(
            final UUID cycleId,
            final UUID dependencyId,
            final UpdateDependencyRequest request,
            final Long callerUserId,
            final Long tenantId) {
        PiCycle cycle = cycleAccessService.resolveCycleForCaller(cycleId, callerUserId, tenantId);
        PiDependency dependency = dependencyRepository.findByIdAndCycleId(dependencyId, cycle.getId())
                .orElseThrow(() -> new PiNotFoundException("PI dependency", dependencyId));
        if (request.status() != null) {
            dependency.setStatus(request.status());
        }
        if (request.note() != null) {
            dependency.setNote(normalize(request.note()));
        }
        return DependencyResponse.from(dependency);
    }

    /**
     * Deletes a dependency.
     *
     * @param cycleId      the cycle UUID
     * @param dependencyId the dependency UUID
     * @param callerUserId the calling user's {@code public.users.id}
     * @param tenantId     the calling tenant's {@code public.tenants.id}
     * @throws PiNotFoundException if the cycle/dependency does not exist, belongs to another
     *     tenant, or the caller has no access to it
     */
    @Transactional
    public void delete(final UUID cycleId, final UUID dependencyId, final Long callerUserId, final Long tenantId) {
        PiCycle cycle = cycleAccessService.resolveCycleForCaller(cycleId, callerUserId, tenantId);
        PiDependency dependency = dependencyRepository.findByIdAndCycleId(dependencyId, cycle.getId())
                .orElseThrow(() -> new PiNotFoundException("PI dependency", dependencyId));
        dependencyRepository.delete(dependency);
    }

    /**
     * Normalizes an optional note — trims, and maps a blank result to {@code null}.
     *
     * @param note the raw note, or {@code null}
     * @return the normalized note, or {@code null}
     */
    private String normalize(final String note) {
        if (note == null) {
            return null;
        }
        String trimmed = note.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}

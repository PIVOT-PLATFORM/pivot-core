package fr.pivot.agilite.pi;

import fr.pivot.agilite.auth.entity.PlatformTeam;
import fr.pivot.agilite.auth.entity.PlatformTeamMember;
import fr.pivot.agilite.auth.repository.PlatformTeamMemberReadRepository;
import fr.pivot.agilite.auth.repository.PlatformTeamReadRepository;
import fr.pivot.agilite.exception.PiNotFoundException;
import fr.pivot.agilite.exception.PiValidationException;
import fr.pivot.agilite.pi.dto.CreateCycleRequest;
import fr.pivot.agilite.pi.dto.CreateTeamRequest;
import fr.pivot.agilite.pi.dto.CycleResponse;
import fr.pivot.agilite.pi.dto.CycleSummaryResponse;
import fr.pivot.agilite.pi.dto.ImportTeamsRequest;
import fr.pivot.agilite.pi.dto.ImportTeamsResponse;
import fr.pivot.agilite.pi.dto.PiCycleTeamResponse;
import fr.pivot.agilite.pi.dto.UpdateCycleRequest;
import fr.pivot.agilite.pi.dto.UpdateIterationRequest;
import fr.pivot.agilite.pi.dto.UpdateTeamRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Business logic for PI cycle, iteration, and Train team operations (US50.1.1).
 *
 * <p>All read operations are wrapped in a read-only transaction; write operations use a full
 * read-write transaction. Cycle access is enforced via {@link PiCycleAccessService} throughout.
 */
@Service
@Transactional(readOnly = true)
public class PiCycleService {

    private static final int MIN_ITERATION_COUNT = 1;
    private static final int MAX_ITERATION_COUNT = 12;
    private static final int MIN_ITERATION_WEEKS = 1;
    private static final int MAX_ITERATION_WEEKS = 6;
    private static final int MAX_NAME_LENGTH = 120;

    private final PiCycleRepository cycleRepository;
    private final PiCycleAccessService cycleAccessService;
    private final PlatformTeamReadRepository teamRepository;
    private final PlatformTeamMemberReadRepository teamMemberRepository;

    /**
     * Creates the service with all required dependencies.
     *
     * @param cycleRepository      repository for cycle persistence
     * @param cycleAccessService   shared cycle-resolution/access-check helper
     * @param teamRepository       read-only access to {@code public.teams}, for team import
     * @param teamMemberRepository read-only access to {@code public.team_members}, for import
     *                             membership checks
     */
    public PiCycleService(
            final PiCycleRepository cycleRepository,
            final PiCycleAccessService cycleAccessService,
            final PlatformTeamReadRepository teamRepository,
            final PlatformTeamMemberReadRepository teamMemberRepository) {
        this.cycleRepository = cycleRepository;
        this.cycleAccessService = cycleAccessService;
        this.teamRepository = teamRepository;
        this.teamMemberRepository = teamMemberRepository;
    }

    /**
     * Creates a new PI cycle with its generated iterations.
     *
     * @param request      the creation request
     * @param callerUserId the calling user's {@code public.users.id}
     * @param tenantId     the calling tenant's {@code public.tenants.id}
     * @return the created cycle as a response record
     * @throws PiValidationException if the name is blank/too long, or the iteration parameters
     *     are out of bounds
     */
    @Transactional
    public CycleResponse create(final CreateCycleRequest request, final Long callerUserId, final Long tenantId) {
        String name = validateName(request.name());
        int count = request.iterationCount() != null ? request.iterationCount() : PiCycle.DEFAULT_ITERATION_COUNT;
        int weeks = request.iterationWeeks() != null ? request.iterationWeeks() : PiCycle.DEFAULT_ITERATION_WEEKS;
        if (count < MIN_ITERATION_COUNT || count > MAX_ITERATION_COUNT
                || weeks < MIN_ITERATION_WEEKS || weeks > MAX_ITERATION_WEEKS) {
            throw new PiValidationException("INVALID_ITERATION_PARAMS", "iterationCount/iterationWeeks out of bounds");
        }

        List<PiIterationGenerator.GeneratedIteration> generated =
                PiIterationGenerator.generate(request.startDate(), count, weeks);
        Instant now = Instant.now();
        PiCycle cycle = new PiCycle(
                tenantId,
                name,
                request.artName(),
                request.startDate(),
                generated.getLast().endDate(),
                callerUserId,
                now);
        for (PiIterationGenerator.GeneratedIteration iteration : generated) {
            cycle.getIterations().add(new PiIteration(
                    cycle, iteration.number(), iteration.label(), iteration.startDate(), iteration.endDate()));
        }
        PiCycle saved = cycleRepository.save(cycle);
        return CycleResponse.from(saved);
    }

    /**
     * Returns a single cycle if the caller has access to it.
     *
     * @param cycleId      the cycle UUID
     * @param callerUserId the calling user's {@code public.users.id}
     * @param tenantId     the calling tenant's {@code public.tenants.id}
     * @return the cycle response
     * @throws PiNotFoundException if the cycle does not exist, belongs to another tenant, or the
     *     caller has no access to it
     */
    public CycleResponse findById(final UUID cycleId, final Long callerUserId, final Long tenantId) {
        return CycleResponse.from(cycleAccessService.resolveCycleForCaller(cycleId, callerUserId, tenantId));
    }

    /**
     * Lists the cycles accessible to the caller (created by them, or with at least one imported
     * Train team they belong to), sorted by {@code startDate} descending.
     *
     * @param callerUserId the calling user's {@code public.users.id}
     * @param tenantId     the calling tenant's {@code public.tenants.id}
     * @return the accessible cycles, most recent {@code startDate} first
     */
    public List<CycleSummaryResponse> list(final Long callerUserId, final Long tenantId) {
        Map<UUID, PiCycle> accessible = new LinkedHashMap<>();
        for (PiCycle cycle : cycleRepository.findAllByCreatedByAndTenantId(callerUserId, tenantId)) {
            accessible.put(cycle.getId(), cycle);
        }
        List<Long> callerTeamIds = teamMemberRepository.findAllByUserId(callerUserId).stream()
                .map(PlatformTeamMember::getTeamId)
                .distinct()
                .toList();
        if (!callerTeamIds.isEmpty()) {
            for (PiCycle cycle : cycleRepository.findAllByTenantIdAndTeamSourceTeamIdIn(tenantId, callerTeamIds)) {
                accessible.put(cycle.getId(), cycle);
            }
        }
        return accessible.values().stream()
                .sorted(Comparator.comparing(PiCycle::getStartDate).reversed())
                .map(CycleSummaryResponse::from)
                .toList();
    }

    /**
     * Updates a cycle's own fields — only non-{@code null} request fields are applied.
     *
     * @param cycleId      the cycle UUID
     * @param request      the update request
     * @param callerUserId the calling user's {@code public.users.id}
     * @param tenantId     the calling tenant's {@code public.tenants.id}
     * @return the updated cycle response
     * @throws PiNotFoundException if the cycle does not exist, belongs to another tenant, or the
     *     caller has no access to it
     * @throws PiValidationException if a supplied name is blank or too long
     */
    @Transactional
    public CycleResponse update(
            final UUID cycleId, final UpdateCycleRequest request, final Long callerUserId, final Long tenantId) {
        PiCycle cycle = cycleAccessService.resolveCycleForCaller(cycleId, callerUserId, tenantId);
        if (request.name() != null) {
            cycle.setName(validateName(request.name()));
        }
        if (request.artName() != null) {
            cycle.setArtName(request.artName());
        }
        if (request.status() != null) {
            cycle.setStatus(request.status());
        }
        if (request.eventDay1() != null) {
            cycle.setEventDay1(request.eventDay1());
        }
        if (request.eventDay2() != null) {
            cycle.setEventDay2(request.eventDay2());
        }
        if (request.eventLocation() != null) {
            cycle.setEventLocation(request.eventLocation());
        }
        return CycleResponse.from(cycle);
    }

    /**
     * Permanently deletes a cycle, cascading to its iterations, Train teams, tickets, and
     * dependencies.
     *
     * @param cycleId      the cycle UUID
     * @param callerUserId the calling user's {@code public.users.id}
     * @param tenantId     the calling tenant's {@code public.tenants.id}
     * @throws PiNotFoundException if the cycle does not exist, belongs to another tenant, or the
     *     caller has no access to it
     */
    @Transactional
    public void delete(final UUID cycleId, final Long callerUserId, final Long tenantId) {
        PiCycle cycle = cycleAccessService.resolveCycleForCaller(cycleId, callerUserId, tenantId);
        cycleRepository.delete(cycle);
    }

    /**
     * Manually adds a Train team to a cycle (no {@code sourceTeamId} — grants no membership-based
     * access to anyone but the cycle's creator).
     *
     * @param cycleId      the cycle UUID
     * @param request      the creation request
     * @param callerUserId the calling user's {@code public.users.id}
     * @param tenantId     the calling tenant's {@code public.tenants.id}
     * @return the created Train team response
     * @throws PiNotFoundException if the cycle does not exist, belongs to another tenant, or the
     *     caller has no access to it
     * @throws PiValidationException if the name is blank or too long
     */
    @Transactional
    public PiCycleTeamResponse addManualTeam(
            final UUID cycleId, final CreateTeamRequest request, final Long callerUserId, final Long tenantId) {
        PiCycle cycle = cycleAccessService.resolveCycleForCaller(cycleId, callerUserId, tenantId);
        String name = validateName(request.name());
        int order = cycle.getTeams().size();
        String color = request.color() != null && !request.color().isBlank()
                ? request.color()
                : PiCycleTeamColors.forOrder(order);
        PiCycleTeam team = new PiCycleTeam(cycle, name, color, order, null);
        cycle.getTeams().add(team);
        // Flush now so the UUID-generated id (client-side, but only assigned at insert time) is
        // populated before building the response — a bare add() to a managed parent's cascade
        // collection defers the actual insert to the next flush, same reasoning as
        // WheelService#create's explicit save().
        cycleRepository.flush();
        return PiCycleTeamResponse.from(team);
    }

    /**
     * Imports one or more PIVOT teams as Train team snapshots — a team already imported (same
     * {@code sourceTeamId}), non-existent, cross-tenant, or the caller isn't a member of, is
     * silently skipped rather than failing the whole batch.
     *
     * @param cycleId      the cycle UUID
     * @param request      the import request (1-30 team ids)
     * @param callerUserId the calling user's {@code public.users.id}
     * @param tenantId     the calling tenant's {@code public.tenants.id}
     * @return the import result — count actually imported and the newly created Train teams
     * @throws PiNotFoundException if the cycle does not exist, belongs to another tenant, or the
     *     caller has no access to it
     * @throws PiValidationException {@code NO_IMPORTABLE_TEAM} if none of the requested ids could
     *     be imported
     */
    @Transactional
    public ImportTeamsResponse importTeams(
            final UUID cycleId, final ImportTeamsRequest request, final Long callerUserId, final Long tenantId) {
        PiCycle cycle = cycleAccessService.resolveCycleForCaller(cycleId, callerUserId, tenantId);
        Set<Long> alreadyImported = new HashSet<>();
        for (PiCycleTeam team : cycle.getTeams()) {
            if (team.getSourceTeamId() != null) {
                alreadyImported.add(team.getSourceTeamId());
            }
        }
        List<PiCycleTeamResponse> imported = new ArrayList<>();
        int order = cycle.getTeams().size();
        for (Long teamId : request.teamIds()) {
            if (alreadyImported.contains(teamId)) {
                continue;
            }
            PlatformTeam platformTeam = teamRepository.findByIdAndTenantId(teamId, tenantId).orElse(null);
            if (platformTeam == null || !teamMemberRepository.existsByTeamIdAndUserId(teamId, callerUserId)) {
                continue;
            }
            String color = PiCycleTeamColors.forOrder(order);
            PiCycleTeam team = new PiCycleTeam(cycle, platformTeam.getName(), color, order, teamId);
            cycle.getTeams().add(team);
            imported.add(PiCycleTeamResponse.from(team));
            alreadyImported.add(teamId);
            order++;
        }
        if (imported.isEmpty()) {
            throw new PiValidationException("NO_IMPORTABLE_TEAM", "No team from the requested list could be imported");
        }
        // Flush now so every imported team's UUID-generated id is populated before building the
        // response — see the identical comment in #addManualTeam.
        cycleRepository.flush();
        return new ImportTeamsResponse(imported.size(), imported);
    }

    /**
     * Updates a Train team's own fields — only non-{@code null} request fields are applied.
     *
     * @param cycleId      the cycle UUID
     * @param teamId       the Train team UUID
     * @param request      the update request
     * @param callerUserId the calling user's {@code public.users.id}
     * @param tenantId     the calling tenant's {@code public.tenants.id}
     * @return the updated Train team response
     * @throws PiNotFoundException if the cycle/team does not exist, belongs to another tenant, or
     *     the caller has no access to it
     */
    @Transactional
    public PiCycleTeamResponse updateTeam(
            final UUID cycleId,
            final UUID teamId,
            final UpdateTeamRequest request,
            final Long callerUserId,
            final Long tenantId) {
        PiCycle cycle = cycleAccessService.resolveCycleForCaller(cycleId, callerUserId, tenantId);
        PiCycleTeam team = findTeamInCycle(cycle, teamId);
        if (request.name() != null) {
            team.setName(validateName(request.name()));
        }
        if (request.color() != null) {
            team.setColor(request.color());
        }
        if (request.order() != null) {
            team.setTeamOrder(request.order());
        }
        return PiCycleTeamResponse.from(team);
    }

    /**
     * Deletes a Train team from a cycle. Its tickets are never deleted — the database FK ({@code
     * ON DELETE SET NULL}) falls them back to the "Unplanned team" (Train row) automatically
     * (US50.3.1).
     *
     * @param cycleId      the cycle UUID
     * @param teamId       the Train team UUID
     * @param callerUserId the calling user's {@code public.users.id}
     * @param tenantId     the calling tenant's {@code public.tenants.id}
     * @throws PiNotFoundException if the cycle/team does not exist, belongs to another tenant, or
     *     the caller has no access to it
     */
    @Transactional
    public void deleteTeam(final UUID cycleId, final UUID teamId, final Long callerUserId, final Long tenantId) {
        PiCycle cycle = cycleAccessService.resolveCycleForCaller(cycleId, callerUserId, tenantId);
        PiCycleTeam team = findTeamInCycle(cycle, teamId);
        cycle.getTeams().remove(team);
    }

    /**
     * Adjusts an already-generated iteration — only non-{@code null} request fields are applied.
     *
     * @param cycleId      the cycle UUID
     * @param iterationId  the iteration UUID
     * @param request      the update request
     * @param callerUserId the calling user's {@code public.users.id}
     * @param tenantId     the calling tenant's {@code public.tenants.id}
     * @return the updated cycle response (the whole cycle, since an iteration edit may affect the
     *     cycle's own {@code endDate} bookkeeping in a future US — kept simple for the socle)
     * @throws PiNotFoundException if the cycle/iteration does not exist, belongs to another
     *     tenant, or the caller has no access to it
     * @throws PiValidationException {@code INVALID_DATE_RANGE} if the resulting start is after
     *     the resulting end
     */
    @Transactional
    public CycleResponse updateIteration(
            final UUID cycleId,
            final UUID iterationId,
            final UpdateIterationRequest request,
            final Long callerUserId,
            final Long tenantId) {
        PiCycle cycle = cycleAccessService.resolveCycleForCaller(cycleId, callerUserId, tenantId);
        PiIteration iteration = cycle.getIterations().stream()
                .filter(candidate -> candidate.getId().equals(iterationId))
                .findFirst()
                .orElseThrow(() -> new PiNotFoundException("PI iteration", iterationId));
        if (request.label() != null) {
            iteration.setLabel(request.label());
        }
        LocalDate newStart = request.startDate() != null ? request.startDate() : iteration.getStartDate();
        LocalDate newEnd = request.endDate() != null ? request.endDate() : iteration.getEndDate();
        if (newStart.isAfter(newEnd)) {
            throw new PiValidationException("INVALID_DATE_RANGE", "startDate must not be after endDate");
        }
        iteration.setStartDate(newStart);
        iteration.setEndDate(newEnd);
        return CycleResponse.from(cycle);
    }

    /**
     * Finds a Train team within an already-resolved cycle's aggregate.
     *
     * @param cycle  the resolved cycle
     * @param teamId the Train team UUID
     * @return the matching team
     * @throws PiNotFoundException if no team with this id belongs to the cycle
     */
    private PiCycleTeam findTeamInCycle(final PiCycle cycle, final UUID teamId) {
        return cycle.getTeams().stream()
                .filter(candidate -> candidate.getId().equals(teamId))
                .findFirst()
                .orElseThrow(() -> new PiNotFoundException("Train team", teamId));
    }

    /**
     * Validates a cycle/team name.
     *
     * @param name the candidate name
     * @return the trimmed, validated name
     * @throws PiValidationException {@code INVALID_NAME} if blank or too long
     */
    private String validateName(final String name) {
        String trimmed = name == null ? "" : name.trim();
        if (trimmed.isEmpty() || trimmed.length() > MAX_NAME_LENGTH) {
            throw new PiValidationException("INVALID_NAME", "name must be 1-" + MAX_NAME_LENGTH + " characters");
        }
        return trimmed;
    }
}

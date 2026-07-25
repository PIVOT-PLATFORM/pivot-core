package fr.pivot.collaboratif.session.kpi;

import fr.pivot.collaboratif.context.CollaboratifRequestPrincipal;
import fr.pivot.collaboratif.exception.KpiNotFoundException;
import fr.pivot.collaboratif.exception.SessionForbiddenException;
import fr.pivot.collaboratif.exception.SessionValidationException;
import fr.pivot.collaboratif.session.kpi.dto.KpiDefinitionResponse;
import fr.pivot.collaboratif.session.kpi.dto.KpiRefResponse;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Business logic for the Session live KPI producer (EN19.4) — lists the domain's {@link
 * SessionKpiDefinition}s and resolves one to a value on demand.
 *
 * <p><strong>Pull model, no cache.</strong> Every {@link #resolve} call recomputes the underlying
 * aggregate fresh via {@link SessionKpiRepository#aggregate} — there is no persisted/cached KPI
 * row anywhere, the same choice {@code fr.pivot.agilite.capacity.kpi.KpiService} already made for
 * its own KPIs. This is also why the {@code kpi.updated} contract's "resolution pull renvoie la
 * même valeur" acceptance criterion holds trivially here: nothing can go stale between the event
 * and the next pull unless the underlying data itself changes again.
 */
@Service
public class SessionKpiService {

    private static final String SOURCE = "collaboratif";
    private static final String SCOPE_TEAM = "team";
    private static final String CODE_UNSUPPORTED_SCOPE = "UNSUPPORTED_KPI_SCOPE";
    private static final String CODE_ACCESS_DENIED = "KPI_ACCESS_DENIED";

    private final SessionKpiRepository kpiRepository;

    /**
     * Creates the service with its required dependency.
     *
     * @param kpiRepository the aggregate query, also used to validate a {@code teamId} scope
     *                       belongs to the caller's tenant (see {@link
     *                       SessionKpiRepository#teamBelongsToTenant})
     */
    public SessionKpiService(final SessionKpiRepository kpiRepository) {
        this.kpiRepository = kpiRepository;
    }

    /**
     * Lists the KPIs liable by the caller, filtered by role (EN28.14 list surface).
     *
     * @param principal the caller
     * @return the visible KPI definitions, each with {@code unit}/{@code supportedScopes}/{@code
     *     refreshHint}
     */
    @Transactional(readOnly = true)
    public List<KpiDefinitionResponse> listDefinitions(final CollaboratifRequestPrincipal principal) {
        return Arrays.stream(SessionKpiDefinition.values())
                .filter(definition -> definition.allowedRoles().contains(principal.role()))
                .map(definition -> new KpiDefinitionResponse(
                        SOURCE,
                        definition.kpiKey(),
                        definition.unit(),
                        definition.supportedScopes(),
                        definition.refreshHint().toString(),
                        definition.allowedRoles()))
                .toList();
    }

    /**
     * Resolves a single KPI's current value for the caller (EN28.14 pull surface).
     *
     * @param kpiKey    the KPI's stable identifier
     * @param scope     {@code "tenant"} or {@code "team"}
     * @param teamId    required and validated when {@code scope} is {@code "team"}, ignored
     *                  otherwise
     * @param principal the caller
     * @return the resolved {@code KpiRef}
     * @throws KpiNotFoundException      if {@code kpiKey} is unknown, or {@code teamId} does not
     *                                    resolve to a team of the caller's tenant
     * @throws SessionForbiddenException if the caller's role is not authorized for this KPI
     * @throws SessionValidationException if {@code scope} is not one of this KPI's {@code
     *                                     supportedScopes}, or {@code teamId} is missing for
     *                                     {@code scope=team}
     */
    @Transactional(readOnly = true)
    public KpiRefResponse resolve(
            final String kpiKey, final String scope, final Long teamId,
            final CollaboratifRequestPrincipal principal) {
        SessionKpiDefinition definition = SessionKpiDefinition.byKey(kpiKey)
                .orElseThrow(() -> new KpiNotFoundException("Unknown KPI: " + kpiKey));
        if (!definition.allowedRoles().contains(principal.role())) {
            throw new SessionForbiddenException(CODE_ACCESS_DENIED, "Role not authorized for this KPI");
        }
        if (!definition.supportedScopes().contains(scope)) {
            throw new SessionValidationException(
                    CODE_UNSUPPORTED_SCOPE, "Scope not supported for this KPI: " + scope);
        }

        Long scopedTeamId = null;
        Map<String, Object> scopeMap = new LinkedHashMap<>();
        if (SCOPE_TEAM.equals(scope)) {
            if (teamId == null) {
                throw new SessionValidationException(CODE_UNSUPPORTED_SCOPE, "teamId is required for scope=team");
            }
            if (!kpiRepository.teamBelongsToTenant(teamId, principal.tenantId())) {
                throw new KpiNotFoundException("Team not found");
            }
            scopedTeamId = teamId;
            scopeMap.put("teamId", teamId);
        }

        SessionKpiAggregate aggregate = kpiRepository.aggregate(principal.tenantId(), scopedTeamId);
        double value = valueOf(definition, aggregate);

        return new KpiRefResponse(
                SOURCE, definition.kpiKey(), principal.tenantId(), scopeMap, definition.supportedScopes(),
                definition.refreshHint().toString(), definition.unit(), value, Instant.now());
    }

    private double valueOf(final SessionKpiDefinition definition, final SessionKpiAggregate aggregate) {
        return switch (definition) {
            case SESSIONS_RUN -> aggregate.getSessionsRun();
            case ACTIVITIES_RUN -> aggregate.getActivitiesRun();
            case AVG_PARTICIPANTS -> aggregate.getAvgParticipants();
            case PARTICIPATION_RATE -> aggregate.getParticipationRate();
            case COMPLETION_RATE -> aggregate.getCompletionRate();
        };
    }
}

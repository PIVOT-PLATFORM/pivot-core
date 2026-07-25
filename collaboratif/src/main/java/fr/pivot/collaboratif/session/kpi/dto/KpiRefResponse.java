package fr.pivot.collaboratif.session.kpi.dto;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * A resolved {@code KpiRef} — the full pull-model payload returned by
 * {@code GET /api/collaboratif/kpi/{kpiKey}}, matching the schema documented by EN28.14.
 *
 * @param source          the emitting domain, always {@code "collaboratif"} for this producer
 * @param kpiKey          the KPI's stable identifier, e.g. {@code "session.sessions_run"}
 * @param tenantId        the resolving caller's tenant — never a cross-tenant value
 * @param scope           the logical filter actually applied, e.g. {@code {"teamId": 42}}; empty
 *                        for a {@code tenant}-scoped resolution
 * @param supportedScopes the granularities this KPI can be resolved at
 * @param refreshHint     ISO-8601 duration string hinting how often a consumer should poll
 * @param unit            the unit {@code value} is expressed in
 * @param value           the resolved value, computed on demand — see {@link
 *                        fr.pivot.collaboratif.session.kpi.SessionKpiService}
 * @param observedAt      when this value was computed
 */
public record KpiRefResponse(
        String source,
        String kpiKey,
        Long tenantId,
        Map<String, Object> scope,
        List<String> supportedScopes,
        String refreshHint,
        String unit,
        double value,
        Instant observedAt) {

    /**
     * Defensively copies the mutable-typed components (SpotBugs {@code EI_EXPOSE_REP2}) — {@link
     * fr.pivot.collaboratif.session.kpi.SessionKpiService} only ever passes a locally-built
     * {@code LinkedHashMap} and {@link fr.pivot.collaboratif.session.kpi.SessionKpiDefinition}'s
     * own already-immutable list, but this record has no control over future callers.
     */
    public KpiRefResponse {
        scope = Map.copyOf(scope);
        supportedScopes = List.copyOf(supportedScopes);
    }

    /**
     * Returns the logical filter actually applied.
     *
     * @return an immutable, defensively-copied map (SpotBugs {@code EI_EXPOSE_REP})
     */
    @Override
    public Map<String, Object> scope() {
        return Map.copyOf(scope);
    }

    /**
     * Returns the granularities this KPI can be resolved at.
     *
     * @return an immutable, defensively-copied list (SpotBugs {@code EI_EXPOSE_REP})
     */
    @Override
    public List<String> supportedScopes() {
        return List.copyOf(supportedScopes);
    }
}

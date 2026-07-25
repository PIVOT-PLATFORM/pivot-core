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
}

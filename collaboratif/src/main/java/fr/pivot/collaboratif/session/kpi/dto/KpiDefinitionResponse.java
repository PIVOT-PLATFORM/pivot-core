package fr.pivot.collaboratif.session.kpi.dto;

import java.util.List;

/**
 * Metadata-only listing entry returned by {@code GET /api/collaboratif/kpi} — the {@code KpiRef}
 * fields that describe a KPI without resolving any value (that is {@link
 * fr.pivot.collaboratif.session.kpi.SessionKpiController}'s pull endpoint's job), per EN28.14's
 * list surface: "chacun déclarant {@code unit}, {@code supportedScopes} et {@code refreshHint}".
 *
 * @param source          the emitting domain, always {@code "collaboratif"} for this producer
 * @param kpiKey          the KPI's stable identifier, e.g. {@code "session.sessions_run"}
 * @param unit            the unit this KPI's value is expressed in
 * @param supportedScopes the granularities this KPI can be resolved at ({@code "tenant"}/{@code
 *                        "team"})
 * @param refreshHint     ISO-8601 duration string hinting how often a consumer should poll
 * @param visibility      the roles allowed to see/resolve this KPI
 */
public record KpiDefinitionResponse(
        String source,
        String kpiKey,
        String unit,
        List<String> supportedScopes,
        String refreshHint,
        List<String> visibility) {
}

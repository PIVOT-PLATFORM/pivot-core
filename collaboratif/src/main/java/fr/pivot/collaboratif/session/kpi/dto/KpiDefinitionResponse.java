package fr.pivot.collaboratif.session.kpi.dto;

import java.util.List;

/**
 * Metadata-only listing entry returned by {@code GET /api/collaboratif/kpi} — the {@code KpiRef}
 * fields that describe a KPI without resolving any value (that is {@code
 * fr.pivot.collaboratif.kpi.CollaboratifKpiController}'s pull endpoint's job), per EN28.14's list
 * surface: "chacun déclarant {@code unit}, {@code supportedScopes} et {@code refreshHint}".
 *
 * <p><strong>Shared across every module domain that produces KPIs</strong> — introduced for
 * {@code fr.pivot.collaboratif.session.kpi.SessionKpiService} (EN19.4) and, unchanged, reused by
 * {@code fr.pivot.collaboratif.meeting.kpi.MeetopsKpiService} (EN12.3) rather than duplicated
 * under a {@code meetops.kpi.dto} package — see {@code
 * fr.pivot.collaboratif.kpi.CollaboratifKpiProvider}'s Javadoc for the seam that lets both domains
 * share this one shape and this one HTTP surface. Still living under {@code session.kpi.dto} (not
 * relocated to a neutral package) purely to keep EN12.3's diff minimal — a follow-up cleanup, not
 * a functional concern.
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

    /**
     * Defensively copies both list components (SpotBugs {@code EI_EXPOSE_REP2}) — every caller of
     * this record's canonical constructor passes {@link fr.pivot.collaboratif.session.kpi.SessionKpiDefinition}'s
     * own already-immutable lists, but this record has no control over future callers.
     */
    public KpiDefinitionResponse {
        supportedScopes = List.copyOf(supportedScopes);
        visibility = List.copyOf(visibility);
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

    /**
     * Returns the roles allowed to see/resolve this KPI.
     *
     * @return an immutable, defensively-copied list (SpotBugs {@code EI_EXPOSE_REP})
     */
    @Override
    public List<String> visibility() {
        return List.copyOf(visibility);
    }
}

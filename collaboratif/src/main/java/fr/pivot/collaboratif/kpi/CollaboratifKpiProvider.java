package fr.pivot.collaboratif.kpi;

import fr.pivot.collaboratif.context.CollaboratifRequestPrincipal;
import fr.pivot.collaboratif.session.kpi.dto.KpiDefinitionResponse;
import fr.pivot.collaboratif.session.kpi.dto.KpiRefResponse;

import java.util.List;

/**
 * A single domain's {@code KpiRef} producer (EN28.14) — implemented once per module domain that
 * exposes KPIs ({@code fr.pivot.collaboratif.session.kpi.SessionKpiService} for EN19.4, {@code
 * fr.pivot.collaboratif.meeting.kpi.MeetopsKpiService} for EN12.3) and aggregated by {@link
 * CollaboratifKpiController} into the single {@code GET /api/collaboratif/kpi} surface EN28.14
 * requires — one endpoint listing/resolving every collaboratif-module KPI, not one per domain.
 *
 * <p><strong>Why this seam exists.</strong> EN19.4 originally exposed its own {@code
 * @RestController} directly on {@code CollaboratifApiPaths.BASE + "/kpi"}. Adding a second,
 * equally direct {@code @RestController} for EN12.3 on the identical path would make Spring throw
 * {@code Ambiguous mapping} at context startup (two controllers registering the same {@code
 * @GetMapping}/{@code @GetMapping("/{kpiKey}")} pair). This interface lets each domain keep its
 * own {@code *KpiService} (and its own unit tests) completely unchanged, while {@link
 * CollaboratifKpiController} is the single place that actually owns the shared route.
 */
public interface CollaboratifKpiProvider {

    /**
     * Lists this domain's KPIs visible to the caller, filtered by role.
     *
     * @param principal the caller
     * @return the visible KPI definitions of this domain only
     */
    List<KpiDefinitionResponse> listDefinitions(CollaboratifRequestPrincipal principal);

    /**
     * Returns whether {@code kpiKey} belongs to this domain — the dispatch key {@link
     * CollaboratifKpiController#resolve} uses to pick which provider's {@link #resolve} to call,
     * so a {@code session.*} key is never routed to the MeetOps provider or vice versa.
     *
     * @param kpiKey the candidate key
     * @return {@code true} if this domain owns {@code kpiKey}
     */
    boolean supports(String kpiKey);

    /**
     * Resolves one of this domain's KPIs — only ever called after {@link #supports} has confirmed
     * ownership of {@code kpiKey}, so an implementation may resolve it unconditionally (no need to
     * re-check for an unknown key here).
     *
     * @param kpiKey    the KPI's stable identifier
     * @param scope     {@code "tenant"} or {@code "team"}
     * @param teamId    required and validated when {@code scope} is {@code "team"}, ignored
     *                  otherwise
     * @param principal the caller
     * @return the resolved {@code KpiRef}
     */
    KpiRefResponse resolve(String kpiKey, String scope, Long teamId, CollaboratifRequestPrincipal principal);
}

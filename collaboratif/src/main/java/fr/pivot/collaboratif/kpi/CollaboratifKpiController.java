package fr.pivot.collaboratif.kpi;

import fr.pivot.collaboratif.context.CollaboratifRequestPrincipal;
import fr.pivot.collaboratif.exception.KpiNotFoundException;
import fr.pivot.collaboratif.session.kpi.dto.KpiDefinitionResponse;
import fr.pivot.collaboratif.session.kpi.dto.KpiRefResponse;
import fr.pivot.collaboratif.web.CollaboratifApiPaths;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * The single REST controller exposing every collaboratif-module domain's {@code KpiRef} producer
 * (EN28.14's two HTTP surfaces: list and pull) — aggregates every {@link CollaboratifKpiProvider}
 * bean (today: {@code fr.pivot.collaboratif.session.kpi.SessionKpiService} for EN19.4's Session
 * live KPIs, {@code fr.pivot.collaboratif.meeting.kpi.MeetopsKpiService} for EN12.3's MeetOps
 * KPIs) so the module presents exactly one {@code GET /api/collaboratif/kpi} contract regardless
 * of how many domains contribute KPIs to it.
 *
 * <p><strong>Replaces the former, EN19.4-only {@code SessionKpiController}</strong> — same
 * {@code @RequestMapping}, same two endpoints, same behavior for every {@code session.*} key; see
 * {@link CollaboratifKpiProvider}'s Javadoc for why a second, EN12.3-only controller on the same
 * path was not an option (Spring {@code Ambiguous mapping} at startup). Full path (including the
 * application context) is {@code /api/collaboratif/kpi}. Requires a valid {@code Authorization:
 * Bearer <token>} header, resolved into a {@link CollaboratifRequestPrincipal}, same as every
 * other endpoint of this module.
 */
@RestController
@RequestMapping(CollaboratifApiPaths.BASE + "/kpi")
public class CollaboratifKpiController {

    private final List<CollaboratifKpiProvider> providers;

    /**
     * Creates the controller with every registered domain KPI provider.
     *
     * @param providers every {@link CollaboratifKpiProvider} bean in the application context
     */
    public CollaboratifKpiController(final List<CollaboratifKpiProvider> providers) {
        this.providers = List.copyOf(providers);
    }

    /**
     * Lists the KPIs liable by the caller across every domain (EN28.14 list surface, US27.8.3's
     * future module selector).
     *
     * @param principal the resolved caller identity
     * @return the visible KPI definitions of every domain, concatenated
     */
    @GetMapping
    public List<KpiDefinitionResponse> list(final CollaboratifRequestPrincipal principal) {
        return providers.stream()
                .flatMap(provider -> provider.listDefinitions(principal).stream())
                .toList();
    }

    /**
     * Resolves one KPI's current value (EN28.14 pull surface) — dispatched to whichever
     * registered domain provider {@link CollaboratifKpiProvider#supports} the given key.
     *
     * @param kpiKey    the KPI's stable identifier, e.g. {@code session.sessions_run} or {@code
     *                  meetops.meetings_run}
     * @param scope     {@code "tenant"} or {@code "team"}
     * @param teamId    required when {@code scope=team}, ignored otherwise
     * @param principal the resolved caller identity
     * @return the resolved {@code KpiRef}
     * @throws KpiNotFoundException if no registered provider owns {@code kpiKey} (404,
     *                               anti-enumeration — indistinguishable from a provider's own
     *                               "unknown key" 404)
     */
    @GetMapping("/{kpiKey}")
    public KpiRefResponse resolve(
            @PathVariable final String kpiKey,
            @RequestParam final String scope,
            @RequestParam(required = false) final Long teamId,
            final CollaboratifRequestPrincipal principal) {
        return providers.stream()
                .filter(provider -> provider.supports(kpiKey))
                .findFirst()
                .map(provider -> provider.resolve(kpiKey, scope, teamId, principal))
                .orElseThrow(() -> new KpiNotFoundException("Unknown KPI: " + kpiKey));
    }
}

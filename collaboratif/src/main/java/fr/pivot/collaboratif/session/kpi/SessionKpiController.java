package fr.pivot.collaboratif.session.kpi;

import fr.pivot.collaboratif.context.CollaboratifRequestPrincipal;
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
 * REST controller exposing the Session live domain's KPI producer (EN19.4) — the two surfaces
 * EN28.14 defines for a domain producer: list and pull. (The third surface, the {@code
 * kpi.updated} push event, has no HTTP shape — see {@link SessionKpiEventPublisher}, wired from
 * {@code ModuleSessionService#start}/{@code #end}.)
 *
 * <p>Full path (including the application context) is {@code /api/collaboratif/kpi}. Requires a
 * valid {@code Authorization: Bearer <token>} header, resolved into a {@link
 * CollaboratifRequestPrincipal}, same as every other endpoint of this module.
 */
@RestController
@RequestMapping(CollaboratifApiPaths.BASE + "/kpi")
public class SessionKpiController {

    private final SessionKpiService kpiService;

    /**
     * Creates the controller with its required service dependency.
     *
     * @param kpiService the EN19.4 KPI listing/resolution service
     */
    public SessionKpiController(final SessionKpiService kpiService) {
        this.kpiService = kpiService;
    }

    /**
     * Lists the KPIs liable by the caller (EN28.14 list surface, US27.8.3's future module
     * selector).
     *
     * @param principal the resolved caller identity
     * @return the visible KPI definitions
     */
    @GetMapping
    public List<KpiDefinitionResponse> list(final CollaboratifRequestPrincipal principal) {
        return kpiService.listDefinitions(principal);
    }

    /**
     * Resolves one KPI's current value (EN28.14 pull surface).
     *
     * @param kpiKey    the KPI's stable identifier, e.g. {@code session.sessions_run}
     * @param scope     {@code "tenant"} or {@code "team"}
     * @param teamId    required when {@code scope=team}, ignored otherwise
     * @param principal the resolved caller identity
     * @return the resolved {@code KpiRef}
     */
    @GetMapping("/{kpiKey}")
    public KpiRefResponse resolve(
            @PathVariable final String kpiKey,
            @RequestParam final String scope,
            @RequestParam(required = false) final Long teamId,
            final CollaboratifRequestPrincipal principal) {
        return kpiService.resolve(kpiKey, scope, teamId, principal);
    }
}

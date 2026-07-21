package fr.pivot.agilite.capacity.kpi;

import fr.pivot.agilite.capacity.kpi.dto.KpiResponse;
import fr.pivot.agilite.context.RequestPrincipal;
import fr.pivot.agilite.web.AgiliteApiPaths;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * REST controller exposing the five E11 capacity KPIs, computed on demand (pull model, no
 * persisted/cached KPI rows) from the {@code agilite.capacity_*} repositories (EN11.2 — Capacity
 * KPI, Wave 2).
 *
 * <p>Full path (including the application context) is {@code /api/agilite/kpi}. Requires a valid
 * {@code Authorization: Bearer <token>} header, resolved into a {@link RequestPrincipal} by
 * {@code RequestPrincipalResolver} (EN08.3), same as every other capacity endpoint.
 */
@RestController
@RequestMapping(AgiliteApiPaths.BASE + "/kpi")
public class KpiController {

    private final KpiService kpiService;

    /**
     * Creates the controller with its required service dependency.
     *
     * @param kpiService the EN11.2 KPI computation service
     */
    public KpiController(final KpiService kpiService) {
        this.kpiService = kpiService;
    }

    /**
     * Returns the team-aggregated KPIs for the team owning {@code eventId}: {@code
     * capacity.taux_utilisation}, {@code capacity.capacite_nette}, {@code
     * capacity.velocite_moyenne}, {@code capacity.taux_absence}, {@code capacity.depassements}.
     *
     * @param eventId   any capacity event id belonging to the team to report on
     * @param principal the resolved caller identity
     * @return the team's KPIs, or HTTP 404 if the event does not exist, belongs to another
     *     tenant, or the caller is not a member of its team
     */
    @GetMapping
    public KpiResponse getKpis(
            @RequestParam final UUID eventId,
            final RequestPrincipal principal) {
        return kpiService.getTeamKpis(eventId, principal.userId(), principal.tenantId());
    }
}

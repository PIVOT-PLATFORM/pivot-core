package fr.pivot.agilite.capacity;

import fr.pivot.agilite.capacity.dto.CapacitySummaryResponse;
import fr.pivot.agilite.context.RequestPrincipal;
import fr.pivot.agilite.web.AgiliteApiPaths;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * REST controller exposing the capacity event summary — per-member breakdown, event totals, PI
 * consolidation, and engagement gauge (F11.6.5 + F11.6.6 — E11 capacity planning) — under {@code
 * /capacity}.
 *
 * <p>Full path (including the application context) is {@code /api/agilite/capacity}. Requires a
 * valid {@code Authorization: Bearer <token>} header, resolved into a {@link RequestPrincipal} by
 * {@code RequestPrincipalResolver} (EN08.3).
 */
@RestController
@RequestMapping(AgiliteApiPaths.BASE + "/capacity")
public class CapacitySummaryController {

    private final CapacitySummaryService summaryService;

    /**
     * Creates the controller with its required service dependency.
     *
     * @param summaryService the capacity summary business logic service
     */
    public CapacitySummaryController(final CapacitySummaryService summaryService) {
        this.summaryService = summaryService;
    }

    /**
     * Returns the full capacity summary of one event, for a caller who is a member of its team.
     *
     * @param id        the event UUID from the path
     * @param principal the resolved caller identity (user + tenant)
     * @return the full summary, or HTTP 404 if the event does not exist, belongs to another
     *     tenant, or the caller is not a member of its team
     */
    @GetMapping("/events/{id}/summary")
    public CapacitySummaryResponse summary(
            @PathVariable final UUID id,
            final RequestPrincipal principal) {
        return summaryService.summarize(id, principal.userId(), principal.tenantId());
    }
}

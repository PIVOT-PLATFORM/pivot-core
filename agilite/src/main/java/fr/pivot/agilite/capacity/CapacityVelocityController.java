package fr.pivot.agilite.capacity;

import fr.pivot.agilite.capacity.dto.EventResponse;
import fr.pivot.agilite.capacity.dto.UpdateVelocityRequest;
import fr.pivot.agilite.capacity.dto.VelocityAverageResponse;
import fr.pivot.agilite.capacity.dto.VelocityForecastResponse;
import fr.pivot.agilite.capacity.dto.VelocityHistoryEntryResponse;
import fr.pivot.agilite.context.RequestPrincipal;
import fr.pivot.agilite.web.AgiliteApiPaths;
import jakarta.validation.Valid;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * REST controller exposing velocity entry/history/average operations under {@code
 * /capacity} (US11.4.1).
 *
 * <p>The full path (including the application context) is {@code /api/agilite/capacity/...}.
 */
@RestController
@RequestMapping(AgiliteApiPaths.BASE + "/capacity")
@Validated
public class CapacityVelocityController {

    private final CapacityVelocityService velocityService;
    private final CapacityVelocityForecastService forecastService;

    /**
     * Creates the controller with its required service dependencies.
     *
     * @param velocityService the velocity business logic service (US11.4.1)
     * @param forecastService the velocity forecast business logic service (US11.6.3)
     */
    public CapacityVelocityController(
            final CapacityVelocityService velocityService, final CapacityVelocityForecastService forecastService) {
        this.velocityService = velocityService;
        this.forecastService = forecastService;
    }

    /**
     * Records committed/completed points on a {@code SPRINT} event.
     *
     * @param eventId   the event UUID from the path
     * @param request   the update request
     * @param principal the resolved caller identity
     * @return the updated event response
     */
    @PatchMapping("/events/{eventId}/velocity")
    public EventResponse updateVelocity(
            @PathVariable final UUID eventId,
            @RequestBody @Valid final UpdateVelocityRequest request,
            final RequestPrincipal principal) {
        return velocityService.updateVelocity(eventId, request, principal.userId(), principal.tenantId());
    }

    /**
     * Lists a team's most recent completed sprints.
     *
     * @param teamId    the team's {@code public.teams.id} from the path
     * @param limit     optional max number of entries, default 10
     * @param principal the resolved caller identity
     * @return the team's velocity history
     */
    @GetMapping("/teams/{teamId}/velocity-history")
    public List<VelocityHistoryEntryResponse> history(
            @PathVariable final Long teamId,
            @RequestParam(required = false) final Integer limit,
            final RequestPrincipal principal) {
        return velocityService.history(teamId, limit, principal.userId(), principal.tenantId());
    }

    /**
     * Computes a team's simple moving average velocity and suggested next-sprint capacity.
     *
     * @param teamId    the team's {@code public.teams.id} from the path
     * @param count     optional number of sprints to average, default 3
     * @param factor    optional multiplier, default 0.85
     * @param principal the resolved caller identity
     * @return the average and suggested capacity
     */
    @GetMapping("/teams/{teamId}/velocity-history/average")
    public VelocityAverageResponse average(
            @PathVariable final Long teamId,
            @RequestParam(required = false) final Integer count,
            @RequestParam(required = false) final Double factor,
            final RequestPrincipal principal) {
        return velocityService.average(teamId, count, factor, principal.userId(), principal.tenantId());
    }

    /**
     * Computes a team's net-person-day-weighted moving-average velocity forecast (US11.6.3).
     *
     * @param teamId    the team's {@code public.teams.id} from the path
     * @param window    optional averaging window, default 3, {@code [1, 10]}
     * @param principal the resolved caller identity
     * @return the forecast response
     */
    @GetMapping("/teams/{teamId}/velocity-forecast")
    public VelocityForecastResponse forecast(
            @PathVariable final Long teamId,
            @RequestParam(required = false) final Integer window,
            final RequestPrincipal principal) {
        return forecastService.forecast(teamId, window, principal.userId(), principal.tenantId());
    }
}

package fr.pivot.agilite.standup;

import fr.pivot.agilite.context.RequestPrincipal;
import fr.pivot.agilite.standup.dto.StandupStatsResponse;
import fr.pivot.agilite.web.AgiliteApiPaths;
import jakarta.validation.constraints.NotNull;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

/**
 * REST controller exposing daily standup statistics under {@code /standup/stats} (US10.3.1).
 *
 * <p>All endpoints require a valid {@code Authorization: Bearer <token>} header, resolved into a
 * {@link RequestPrincipal} by {@link fr.pivot.agilite.context.RequestPrincipalResolver}.
 *
 * <p>The full path (including the application context) is {@code /api/agilite/standup/stats}.
 */
@RestController
@RequestMapping(AgiliteApiPaths.BASE + "/standup/stats")
@Validated
public class StandupStatsController {

    private final StandupStatsService statsService;

    /**
     * Creates the controller with its required service dependency.
     *
     * @param statsService the standup statistics business logic service
     */
    public StandupStatsController(final StandupStatsService statsService) {
        this.statsService = statsService;
    }

    /**
     * Returns a team's completed-session and per-participant speaking statistics over a period.
     *
     * @param teamId    the team's {@code public.teams.id}
     * @param from      inclusive lower bound (ISO-8601 date), {@code null} to default to
     *                  {@code to - 30 days}
     * @param to        inclusive upper bound (ISO-8601 date), {@code null} to default to today
     * @param principal the resolved caller identity
     * @return the team's stats for the resolved period
     */
    @GetMapping
    public StandupStatsResponse getStats(
            @RequestParam @NotNull final Long teamId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) final LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) final LocalDate to,
            final RequestPrincipal principal) {
        return statsService.getStats(teamId, from, to, principal.userId(), principal.tenantId());
    }
}

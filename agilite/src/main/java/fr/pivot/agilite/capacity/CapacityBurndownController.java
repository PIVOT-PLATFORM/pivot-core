package fr.pivot.agilite.capacity;

import fr.pivot.agilite.capacity.dto.BurndownResponse;
import fr.pivot.agilite.capacity.dto.UpsertBurndownEntryRequest;
import fr.pivot.agilite.context.RequestPrincipal;
import fr.pivot.agilite.web.AgiliteApiPaths;
import jakarta.validation.Valid;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.UUID;

/**
 * REST controller exposing burndown entry/chart operations under {@code
 * /capacity/events/{eventId}/burndown} (US11.4.2).
 *
 * <p>The full path (including the application context) is {@code
 * /api/agilite/capacity/events/{eventId}/burndown}.
 */
@RestController
@RequestMapping(AgiliteApiPaths.BASE + "/capacity/events/{eventId}/burndown")
@Validated
public class CapacityBurndownController {

    private final CapacityBurndownService burndownService;

    /**
     * Creates the controller with its required service dependency.
     *
     * @param burndownService the burndown business logic service (US11.4.2)
     */
    public CapacityBurndownController(final CapacityBurndownService burndownService) {
        this.burndownService = burndownService;
    }

    /**
     * Idempotently records (inserts or replaces) a single day's points-remaining entry.
     *
     * @param eventId   the event UUID from the path
     * @param date      the calendar date from the path
     * @param request   the upsert request
     * @param principal the resolved caller identity
     */
    @PutMapping("/{date}")
    public void upsertEntry(
            @PathVariable final UUID eventId,
            @PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) final LocalDate date,
            @RequestBody @Valid final UpsertBurndownEntryRequest request,
            final RequestPrincipal principal) {
        burndownService.upsertEntry(eventId, date, request.pointsRemaining(), principal.userId(), principal.tenantId());
    }

    /**
     * Returns the full burndown chart payload for a {@code SPRINT} event.
     *
     * @param eventId   the event UUID from the path
     * @param principal the resolved caller identity
     * @return the burndown response
     */
    @GetMapping
    public BurndownResponse getBurndown(@PathVariable final UUID eventId, final RequestPrincipal principal) {
        return burndownService.getBurndown(eventId, principal.userId(), principal.tenantId());
    }
}

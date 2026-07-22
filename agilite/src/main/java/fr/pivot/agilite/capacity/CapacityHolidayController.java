package fr.pivot.agilite.capacity;

import fr.pivot.agilite.capacity.dto.CreateHolidayRequest;
import fr.pivot.agilite.capacity.dto.HolidayResponse;
import fr.pivot.agilite.context.RequestPrincipal;
import fr.pivot.agilite.web.AgiliteApiPaths;
import jakarta.validation.Valid;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * REST controller exposing tenant holiday CRUD under {@code /capacity/holidays} (US11.6.1).
 *
 * <p>The full path (including the application context) is {@code /api/agilite/capacity/holidays}.
 */
@RestController
@RequestMapping(AgiliteApiPaths.BASE + "/capacity/holidays")
@Validated
public class CapacityHolidayController {

    private final CapacityHolidayService holidayService;

    /**
     * Creates the controller with its required service dependency.
     *
     * @param holidayService the holiday business logic service (US11.6.1)
     */
    public CapacityHolidayController(final CapacityHolidayService holidayService) {
        this.holidayService = holidayService;
    }

    /**
     * Adds a new tenant holiday — requires the caller to be a tenant administrator.
     *
     * @param request   the creation request
     * @param principal the resolved caller identity
     * @return the created holiday with HTTP 201 Created
     */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public HolidayResponse create(@RequestBody @Valid final CreateHolidayRequest request, final RequestPrincipal principal) {
        return holidayService.create(request, principal.role(), principal.tenantId());
    }

    /**
     * Lists the caller's tenant holidays, optionally filtered by period.
     *
     * @param from      optional period start, inclusive
     * @param to        optional period end, inclusive
     * @param principal the resolved caller identity
     * @return the tenant's holidays, ordered by date
     */
    @GetMapping
    public List<HolidayResponse> list(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) final LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) final LocalDate to,
            final RequestPrincipal principal) {
        return holidayService.list(from, to, principal.tenantId());
    }

    /**
     * Deletes a tenant holiday — requires the caller to be a tenant administrator.
     *
     * @param holidayId the holiday UUID from the path
     * @param principal the resolved caller identity
     */
    @DeleteMapping("/{holidayId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable final UUID holidayId, final RequestPrincipal principal) {
        holidayService.delete(holidayId, principal.role(), principal.tenantId());
    }
}

package fr.pivot.agilite.capacity;

import fr.pivot.agilite.capacity.dto.CreateHolidayRequest;
import fr.pivot.agilite.capacity.dto.HolidayResponse;
import fr.pivot.agilite.exception.CapacityAdminOnlyException;
import fr.pivot.agilite.exception.CapacityNotFoundException;
import fr.pivot.agilite.exception.CapacityValidationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Business logic for a tenant's manually-maintained holiday list (US11.6.1).
 *
 * <p>Every mutating operation requires the caller to hold {@code ROLE_ADMIN} — see {@link
 * #requireTenantAdmin(String)}.
 */
@Service
@Transactional
public class CapacityHolidayService {

    private static final String ROLE_ADMIN = "ROLE_ADMIN";
    private static final int MAX_LABEL_LENGTH = 100;

    private final CapacityHolidayRepository holidayRepository;

    /**
     * Creates the service with its required dependency.
     *
     * @param holidayRepository repository for holiday persistence
     */
    public CapacityHolidayService(final CapacityHolidayRepository holidayRepository) {
        this.holidayRepository = holidayRepository;
    }

    /**
     * Adds a new tenant holiday.
     *
     * @param request      the creation request
     * @param callerRole   the calling principal's Spring Security role
     * @param tenantId     the calling tenant's {@code public.tenants.id}
     * @return the created holiday's response
     */
    public HolidayResponse create(final CreateHolidayRequest request, final String callerRole, final Long tenantId) {
        requireTenantAdmin(callerRole);
        if (request.label() == null || request.label().isBlank() || request.label().length() > MAX_LABEL_LENGTH) {
            throw new CapacityValidationException("INVALID_HOLIDAY", "label must be 1-" + MAX_LABEL_LENGTH + " characters");
        }
        if (holidayRepository.existsByTenantIdAndDate(tenantId, request.date())) {
            throw new CapacityValidationException("DUPLICATE_HOLIDAY", "A holiday already exists on this date");
        }
        CapacityHoliday holiday = holidayRepository.save(new CapacityHoliday(tenantId, request.date(), request.label()));
        return toResponse(holiday);
    }

    /**
     * Lists a tenant's holidays, optionally filtered by period — readable by any authenticated
     * caller of the tenant, unlike the admin-gated write operations (a holiday list is not
     * sensitive information).
     *
     * @param from     optional period start, inclusive
     * @param to       optional period end, inclusive
     * @param tenantId the calling tenant's {@code public.tenants.id}
     * @return the tenant's holidays, ordered by date
     */
    @Transactional(readOnly = true)
    public List<HolidayResponse> list(final LocalDate from, final LocalDate to, final Long tenantId) {
        List<CapacityHoliday> holidays = from != null && to != null
                ? holidayRepository.findAllByTenantIdAndDateBetweenOrderByDateAsc(tenantId, from, to)
                : holidayRepository.findAllByTenantIdOrderByDateAsc(tenantId);
        return holidays.stream().map(this::toResponse).toList();
    }

    /**
     * Deletes a tenant holiday.
     *
     * @param holidayId  the holiday UUID
     * @param callerRole the calling principal's Spring Security role
     * @param tenantId   the calling tenant's {@code public.tenants.id}
     */
    public void delete(final UUID holidayId, final String callerRole, final Long tenantId) {
        requireTenantAdmin(callerRole);
        CapacityHoliday holiday = holidayRepository.findByIdAndTenantId(holidayId, tenantId)
                .orElseThrow(() -> new CapacityNotFoundException("holiday", holidayId));
        holidayRepository.delete(holiday);
    }

    /**
     * Returns the set of a tenant's holiday dates, for use by {@link CapacityCalculator}'s
     * holiday-aware overloads (US11.6.5) — internal, package-visible helper, not exposed as a
     * public read path (the paginated/ordered {@link #list} above is the public one).
     *
     * @param tenantId the calling tenant's {@code public.tenants.id}
     * @return the tenant's holiday dates
     */
    Set<LocalDate> holidayDatesForTenant(final Long tenantId) {
        return holidayRepository.findAllByTenantIdOrderByDateAsc(tenantId).stream()
                .map(CapacityHoliday::getDate)
                .collect(Collectors.toSet());
    }

    private void requireTenantAdmin(final String callerRole) {
        if (!ROLE_ADMIN.equals(callerRole)) {
            throw new CapacityAdminOnlyException();
        }
    }

    private HolidayResponse toResponse(final CapacityHoliday holiday) {
        return new HolidayResponse(holiday.getId(), holiday.getDate(), holiday.getLabel());
    }
}

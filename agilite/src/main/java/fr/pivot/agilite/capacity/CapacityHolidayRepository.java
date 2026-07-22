package fr.pivot.agilite.capacity;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Spring Data JPA repository for {@link CapacityHoliday} entities (US11.6.1).
 */
public interface CapacityHolidayRepository extends JpaRepository<CapacityHoliday, UUID> {

    /**
     * Lists a tenant's holidays, ordered by date.
     *
     * @param tenantId the owning tenant's id
     * @return the tenant's holidays, ordered by {@code date} ascending
     */
    List<CapacityHoliday> findAllByTenantIdOrderByDateAsc(Long tenantId);

    /**
     * Lists a tenant's holidays falling within {@code [from, to]}, inclusive.
     *
     * @param tenantId the owning tenant's id
     * @param from     range start, inclusive
     * @param to       range end, inclusive
     * @return the matching holidays, ordered by {@code date} ascending
     */
    List<CapacityHoliday> findAllByTenantIdAndDateBetweenOrderByDateAsc(Long tenantId, LocalDate from, LocalDate to);

    /**
     * Finds a holiday by id, scoped to the expected tenant.
     *
     * @param id       the holiday UUID
     * @param tenantId the expected owning tenant's id
     * @return the matching holiday, or empty if not found or owned by another tenant
     */
    Optional<CapacityHoliday> findByIdAndTenantId(UUID id, Long tenantId);

    /**
     * Checks whether a tenant already has a holiday on a given date (US11.6.1's duplicate guard).
     *
     * @param tenantId the owning tenant's id
     * @param date     the candidate date
     * @return {@code true} if a holiday already exists for that tenant/date pair
     */
    boolean existsByTenantIdAndDate(Long tenantId, LocalDate date);
}

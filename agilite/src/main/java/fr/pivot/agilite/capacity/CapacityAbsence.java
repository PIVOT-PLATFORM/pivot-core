package fr.pivot.agilite.capacity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * JPA entity backing a working-day (or half-day) absence of a {@link CapacityEventMember} (E11 —
 * capacity planning), table {@code agilite.capacity_absence}.
 *
 * <p>Ported from the PouetPouet POC's {@code CapacityAbsence} Prisma model, with one deliberate
 * RGPD-driven change: the POC's {@code reason} field is dropped entirely — this table carries no
 * motif and no health-data column of any kind, a structural (schema-level) guarantee rather than
 * an application-layer promise, same posture as {@code agilite.retro_cards}' anonymity {@code
 * CHECK} constraint.
 *
 * <p>{@link #source} is {@code "MANUAL"} for a directly-entered absence, or {@code "IMPORT:*"}
 * (e.g. {@code "IMPORT:teams"}) for one ingested by a future import connector — free-form beyond
 * the {@code "MANUAL"} literal, not a fixed enum, so a new connector needs no schema change.
 */
@Entity
@Table(name = "capacity_absence", schema = "agilite")
public class CapacityAbsence {

    /** Full working day off. */
    public static final double FRACTION_FULL_DAY = 1.0;

    /** Half working day off. */
    public static final double FRACTION_HALF_DAY = 0.5;

    /** {@link #source} value for a directly-entered absence. */
    public static final String SOURCE_MANUAL = "MANUAL";

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "event_member_id", nullable = false)
    private UUID eventMemberId;

    @Column(name = "start_date", nullable = false)
    private LocalDate startDate;

    @Column(name = "end_date", nullable = false)
    private LocalDate endDate;

    /** {@link #FRACTION_FULL_DAY} or {@link #FRACTION_HALF_DAY}. */
    @Column(name = "fraction", nullable = false)
    private double fraction;

    /** {@link #SOURCE_MANUAL} or {@code "IMPORT:*"}. */
    @Column(name = "source", nullable = false, length = 60)
    private String source;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    /** No-argument constructor required by JPA. */
    protected CapacityAbsence() {
    }

    /**
     * Creates a new absence ready to persist.
     *
     * @param eventMemberId the owning event member's identifier
     * @param startDate     the absence's first calendar day (inclusive)
     * @param endDate       the absence's last calendar day (inclusive), never before {@code startDate}
     * @param fraction      {@link #FRACTION_FULL_DAY} or {@link #FRACTION_HALF_DAY}
     * @param source        {@link #SOURCE_MANUAL} or {@code "IMPORT:*"}
     * @param createdAt     creation timestamp
     */
    public CapacityAbsence(
            final UUID eventMemberId,
            final LocalDate startDate,
            final LocalDate endDate,
            final double fraction,
            final String source,
            final Instant createdAt) {
        this.eventMemberId = eventMemberId;
        this.startDate = startDate;
        this.endDate = endDate;
        this.fraction = fraction;
        this.source = source;
        this.createdAt = createdAt;
    }

    /** @return database primary key */
    public UUID getId() {
        return id;
    }

    /** @return the owning event member's identifier */
    public UUID getEventMemberId() {
        return eventMemberId;
    }

    /** @return the absence's first calendar day (inclusive) */
    public LocalDate getStartDate() {
        return startDate;
    }

    /** @return the absence's last calendar day (inclusive) */
    public LocalDate getEndDate() {
        return endDate;
    }

    /** @return {@link #FRACTION_FULL_DAY} or {@link #FRACTION_HALF_DAY} */
    public double getFraction() {
        return fraction;
    }

    /** @return {@link #SOURCE_MANUAL} or an {@code "IMPORT:*"} connector key */
    public String getSource() {
        return source;
    }

    /** @return the creation timestamp */
    public Instant getCreatedAt() {
        return createdAt;
    }
}

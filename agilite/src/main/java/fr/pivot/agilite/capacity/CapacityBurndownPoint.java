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
 * JPA entity backing one (event, day) burndown reading (E11 — capacity planning), table {@code
 * agilite.capacity_burndown_point}.
 *
 * <p>At most one point per {@code (eventId, date)} (unique database constraint) — a later date's
 * reading for the same event is an upsert at the (future) service layer, not an insert.
 */
@Entity
@Table(name = "capacity_burndown_point", schema = "agilite")
public class CapacityBurndownPoint {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "event_id", nullable = false)
    private UUID eventId;

    @Column(name = "date", nullable = false)
    private LocalDate date;

    @Column(name = "points_restants", nullable = false)
    private double pointsRestants;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    /** No-argument constructor required by JPA. */
    protected CapacityBurndownPoint() {
    }

    /**
     * Creates a new burndown point ready to persist.
     *
     * @param eventId        the owning event's identifier
     * @param date           the reading's calendar day
     * @param pointsRestants the remaining points as of {@code date}
     * @param createdAt      creation timestamp
     */
    public CapacityBurndownPoint(
            final UUID eventId,
            final LocalDate date,
            final double pointsRestants,
            final Instant createdAt) {
        this.eventId = eventId;
        this.date = date;
        this.pointsRestants = pointsRestants;
        this.createdAt = createdAt;
    }

    /** @return database primary key */
    public UUID getId() {
        return id;
    }

    /** @return the owning event's identifier */
    public UUID getEventId() {
        return eventId;
    }

    /** @return the reading's calendar day */
    public LocalDate getDate() {
        return date;
    }

    /** @return the remaining points as of {@link #getDate()} */
    public double getPointsRestants() {
        return pointsRestants;
    }

    /** @return the creation timestamp */
    public Instant getCreatedAt() {
        return createdAt;
    }
}

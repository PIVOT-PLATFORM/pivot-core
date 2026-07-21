package fr.pivot.agilite.capacity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * JPA entity backing one finished sprint's velocity snapshot (E11 — capacity planning), table
 * {@code agilite.capacity_velocity}.
 *
 * <p>Feeds {@code fr.pivot.agilite.capacity.calc.CapacityCalculator}'s rolling-N-1
 * velocity/coefficient-of-variation forecast. Deliberately denormalized from the owning {@link
 * CapacityEvent}'s own {@code committedPoints}/{@code completedPoints} at sprint-close time (a
 * later wave's responsibility) rather than re-derived by a join, so the velocity history a
 * forecast is built from stays stable even if the source event is later edited. At most one row
 * per sprint (unique database constraint) — writing a second closes-out is an upsert, not an
 * insert, at the (future) service layer.
 */
@Entity
@Table(name = "capacity_velocity", schema = "agilite")
public class CapacityVelocity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "sprint_event_id", nullable = false, unique = true)
    private UUID sprintEventId;

    @Column(name = "points_engages", nullable = false)
    private double pointsEngages;

    @Column(name = "points_livres", nullable = false)
    private double pointsLivres;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    /** No-argument constructor required by JPA. */
    protected CapacityVelocity() {
    }

    /**
     * Creates a new velocity snapshot ready to persist.
     *
     * @param sprintEventId the closed sprint's identifier, unique across this table
     * @param pointsEngages the sprint's committed points
     * @param pointsLivres  the sprint's completed points
     * @param createdAt     creation timestamp
     */
    public CapacityVelocity(
            final UUID sprintEventId,
            final double pointsEngages,
            final double pointsLivres,
            final Instant createdAt) {
        this.sprintEventId = sprintEventId;
        this.pointsEngages = pointsEngages;
        this.pointsLivres = pointsLivres;
        this.createdAt = createdAt;
    }

    /** @return database primary key */
    public UUID getId() {
        return id;
    }

    /** @return the closed sprint's identifier */
    public UUID getSprintEventId() {
        return sprintEventId;
    }

    /** @return the sprint's committed points */
    public double getPointsEngages() {
        return pointsEngages;
    }

    /** @return the sprint's completed points */
    public double getPointsLivres() {
        return pointsLivres;
    }

    /** @return the creation timestamp */
    public Instant getCreatedAt() {
        return createdAt;
    }
}

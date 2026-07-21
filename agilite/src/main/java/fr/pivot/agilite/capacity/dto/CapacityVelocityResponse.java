package fr.pivot.agilite.capacity.dto;

import fr.pivot.agilite.capacity.CapacityVelocity;

import java.time.Instant;
import java.util.UUID;

/**
 * Response for the upserted velocity snapshot of a sprint ({@code PATCH
 * /capacity/events/{id}/velocity}, F11.4).
 *
 * @param id            the velocity snapshot's primary key
 * @param sprintEventId the sprint's identifier
 * @param pointsEngages the sprint's committed points
 * @param pointsLivres  the sprint's completed points
 * @param createdAt     the snapshot's creation timestamp (first upsert)
 */
public record CapacityVelocityResponse(
        UUID id,
        UUID sprintEventId,
        double pointsEngages,
        double pointsLivres,
        Instant createdAt) {

    /**
     * Builds the response from a persisted {@link CapacityVelocity} entity.
     *
     * @param velocity the entity to project
     * @return a populated response record
     */
    public static CapacityVelocityResponse from(final CapacityVelocity velocity) {
        return new CapacityVelocityResponse(
                velocity.getId(),
                velocity.getSprintEventId(),
                velocity.getPointsEngages(),
                velocity.getPointsLivres(),
                velocity.getCreatedAt());
    }
}

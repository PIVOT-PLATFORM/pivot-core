package fr.pivot.collaboratif.session.postitrush;

import jakarta.persistence.Embeddable;

import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

/**
 * Composite primary key for {@link SessionPostitRushParticipantRound}.
 *
 * <p>Combines {@code roundId} and {@code participantId} — one score/combo row per participant per
 * round. Implements {@link Serializable} as required by JPA for embeddable primary keys.
 */
@Embeddable
public class SessionPostitRushParticipantRoundId implements Serializable {

    /** The round part of the composite key. */
    private UUID roundId;

    /** The participant part of the composite key. */
    private UUID participantId;

    /** No-arg constructor required by JPA. */
    protected SessionPostitRushParticipantRoundId() {
    }

    /**
     * Creates a composite key for the given round and participant.
     *
     * @param roundId       the round UUID
     * @param participantId the participant UUID
     */
    public SessionPostitRushParticipantRoundId(final UUID roundId, final UUID participantId) {
        this.roundId = roundId;
        this.participantId = participantId;
    }

    /**
     * Returns the round UUID.
     *
     * @return the roundId
     */
    public UUID getRoundId() {
        return roundId;
    }

    /**
     * Returns the participant UUID.
     *
     * @return the participantId
     */
    public UUID getParticipantId() {
        return participantId;
    }

    /**
     * {@inheritDoc}
     *
     * <p>Two instances are equal when both their {@code roundId} and {@code participantId} fields
     * are equal.
     */
    @Override
    public boolean equals(final Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof SessionPostitRushParticipantRoundId that)) {
            return false;
        }
        return Objects.equals(roundId, that.roundId)
                && Objects.equals(participantId, that.participantId);
    }

    /** {@inheritDoc} */
    @Override
    public int hashCode() {
        return Objects.hash(roundId, participantId);
    }
}

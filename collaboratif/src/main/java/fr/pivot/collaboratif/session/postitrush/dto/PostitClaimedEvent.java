package fr.pivot.collaboratif.session.postitrush.dto;

import java.util.UUID;

/**
 * STOMP broadcast {@code POSTIT_CLAIMED} (US47.2.1) — the post-it disappears for every client on
 * receipt, whether or not they were the claimant.
 *
 * @param type          discriminator, always {@code "POSTIT_CLAIMED"}
 * @param postitId      the claimed post-it's id
 * @param participantId the claiming participant's id
 */
public record PostitClaimedEvent(String type, UUID postitId, UUID participantId) {

    /**
     * Creates the event with its fixed discriminator.
     *
     * @param postitId      the claimed post-it's id
     * @param participantId the claiming participant's id
     */
    public PostitClaimedEvent(final UUID postitId, final UUID participantId) {
        this("POSTIT_CLAIMED", postitId, participantId);
    }
}

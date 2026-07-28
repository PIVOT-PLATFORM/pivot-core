package fr.pivot.collaboratif.session.postitrush.dto;

import java.util.UUID;

/**
 * STOMP broadcast {@code POSTIT_EXPIRED} (US47.2.1) — a live post-it's lifespan elapsed unclaimed.
 *
 * @param type     discriminator, always {@code "POSTIT_EXPIRED"}
 * @param postitId the expired post-it's id
 */
public record PostitExpiredEvent(String type, UUID postitId) {

    /**
     * Creates the event with its fixed discriminator.
     *
     * @param postitId the expired post-it's id
     */
    public PostitExpiredEvent(final UUID postitId) {
        this("POSTIT_EXPIRED", postitId);
    }
}

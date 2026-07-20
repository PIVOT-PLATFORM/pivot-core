package fr.pivot.collaboratif.whiteboard.member.event;

import java.util.UUID;

/**
 * Spring application event requesting an in-app notification for a board-membership change
 * (US08.2.5) — the hand-off contract this module uses to reach the shared, platform-wide
 * notification system without a compile-time dependency on it.
 *
 * <p><strong>Why an event, not a direct call.</strong> {@code fr.pivot.notification.*} lives in
 * the {@code app} Maven module's source tree, and {@code app} depends on {@code collaboratif} —
 * not the other way around (see the reactor's declared module order in the root {@code pom.xml}:
 * {@code starter, agilite, collaboratif, app}). A direct import from this module would be a
 * circular Maven dependency and fails the build. Publishing this event and letting a listener
 * living in {@code app} (which sees both this module's types and {@code fr.pivot.notification} at
 * compile time) bridge it is the same pattern already used for
 * {@link fr.pivot.collaboratif.whiteboard.canvas.opengraph.CardContentEnrichmentRequestedEvent},
 * and the one {@code fr.pivot.notification.listener}'s package Javadoc documents as the intended
 * integration point for producers outside the notification module's own package tree.
 *
 * <p>{@link fr.pivot.collaboratif.whiteboard.member.BoardMemberService} publishes this via {@link
 * org.springframework.context.ApplicationEventPublisher#publishEvent(Object)} from inside its own
 * {@code @Transactional} methods — the listener consumes it as a
 * {@code @TransactionalEventListener(phase = AFTER_COMMIT)}, so a rolled-back membership mutation
 * never produces a notification for a change that didn't actually happen.
 *
 * @param recipientUserId the {@code public.users.id} of the user to notify
 * @param kind            which of the three US08.2.5 notification types this is
 * @param boardId         the board UUID (defence in depth / future filtering, currently unused
 *                        by the listener)
 * @param boardTitle      the board's title at the time of the change, substituted into the
 *                        notification's {@code {0}} message argument
 * @param role            the member's role after the change, substituted into the {@code {1}}
 *                        argument; {@code null} for {@link Kind#ACCESS_REVOKED}, which has none
 */
public record BoardMembershipNotificationRequestedEvent(
        Long recipientUserId,
        Kind kind,
        UUID boardId,
        String boardTitle,
        String role) {

    /** Which US08.2.5 notification this event requests. */
    public enum Kind {
        /** A brand-new membership was created (invite-by-email, first time). */
        SHARED,
        /** An existing member's role changed (re-invite with a new role, or PATCH role). */
        ROLE_CHANGED,
        /** A member was removed from the board. */
        ACCESS_REVOKED
    }
}

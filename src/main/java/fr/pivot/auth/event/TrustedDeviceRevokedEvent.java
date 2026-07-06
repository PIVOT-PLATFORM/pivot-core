package fr.pivot.auth.event;

import java.time.Instant;

/**
 * Published when a trusted device is revoked, either by the owning user (self-service,
 * US01.4.2) — a listener for account-security notification email is a known future consumer.
 *
 * <p><strong>Known integration point:</strong> this event is the wiring point for US01.5.1
 * ("notification email sur action sensible"), which was not yet implemented on {@code main}
 * at the time US01.4.2 was built. {@link TrustedDeviceRevokedEvent} is published (after the
 * device row is deleted) so that whoever finishes US01.5.1 can add an {@code @EventListener}
 * (or {@code @TransactionalEventListener}) that sends the notification — no listener exists yet.
 * This is a deliberate, documented integration point, not a TODO left silently in code.
 *
 * <p>Carries the minimal set of primitive fields the eventual listener needs to compose the
 * email — {@code userEmail}, {@code userFirstName} and {@code userLocale} mirror exactly the
 * parameters {@link fr.pivot.auth.service.EmailService} methods already take (e.g.
 * {@code user.getEmail()}, {@code user.getFirstName()}, {@code user.getLocale()}) — rather than
 * the {@link fr.pivot.auth.entity.User} entity itself, consistent with the precedent set by
 * {@link fr.pivot.core.modules.event.ModuleActivatedEvent} (primitive identifiers only, never the
 * JPA entity, avoiding both entity-leak-across-module-boundary and lazy-loading pitfalls on a
 * possibly-detached entity by the time an async listener runs).
 *
 * <p>No sealed interface is introduced for this — unlike {@code ModuleLifecycleEvent}, there is
 * only this single event type in this domain today.
 *
 * @param userId        id of the user the revoked device belonged to
 * @param userEmail     the user's email — notification recipient
 * @param userFirstName the user's first name — for email personalization
 * @param userLocale    the user's locale — for email localization
 * @param deviceName    human-readable label of the revoked device, or {@code null} if none was
 *                      captured
 * @param occurredAt    when the revocation happened
 */
public record TrustedDeviceRevokedEvent(
    Long userId,
    String userEmail,
    String userFirstName,
    String userLocale,
    String deviceName,
    Instant occurredAt) {
}

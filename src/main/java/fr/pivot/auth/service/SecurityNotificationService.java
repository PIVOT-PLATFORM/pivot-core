package fr.pivot.auth.service;

import fr.pivot.auth.entity.User;
import org.springframework.stereotype.Service;

import java.time.Instant;

/**
 * Single entry point for every "sensitive action confirmation" email (US01.5.1): password
 * changed, email changed, account deletion requested, and session(s) revoked.
 *
 * <p>Centralizes the policy of <em>which</em> user actions trigger a security notification and
 * <em>who</em> it goes to, while delegating the actual template rendering and dispatch to
 * {@link EmailService} — every notification sent from here is async (inherited from the
 * underlying {@link EmailService} methods, all {@code @Async}) so the triggering API request
 * never blocks on mail delivery.
 *
 * <p>Callers ({@link AccountPasswordService}, {@link PasswordService}, {@link EmailChangeService},
 * {@code AccountDeletionService}, {@link SessionService}) no longer need to know which template,
 * subject key or "secure my account" URL corresponds to their action — they just report what
 * happened and to whom.
 *
 * <p><strong>Bulk session revocation:</strong> {@link #notifySessionsRevoked} takes a
 * {@code revokedCount} rather than being called once per session — {@code DELETE
 * /api/account/sessions} (revoke-all-except-current) calls it exactly once with the total count,
 * producing a single summary email instead of one per revoked session. A count of zero (nothing
 * was actually revoked) is a silent no-op — there is nothing to alert the user about.
 */
@Service
public class SecurityNotificationService {

    private final EmailService emailService;

    /**
     * Constructs the service with its required collaborator.
     *
     * @param emailService transactional email sender — owns templates, subjects and locale-aware
     *                     rendering for every notification dispatched from here
     */
    public SecurityNotificationService(final EmailService emailService) {
        this.emailService = emailService;
    }

    /**
     * Notifies the user that their password was changed — whether via the authenticated
     * "change my password" flow or the unauthenticated forgot/reset-password flow.
     *
     * @param user      the account whose password changed
     * @param changedAt when the change took effect
     * @param ip        client IP of the request that performed the change (may be {@code null})
     */
    public void notifyPasswordChanged(final User user, final Instant changedAt, final String ip) {
        emailService.sendPasswordChangedEmail(
            user.getEmail(), user.getFirstName(), changedAt, ip, EmailService.toLocale(user.getLocale()));
    }

    /**
     * Notifies the user that their email address was changed — sent to the OLD address (the
     * only place this notice can still reach the legitimate owner if the change was not
     * authorized), never to the new one.
     *
     * @param user      the account whose email changed (already updated to {@code newEmail} by
     *                  the time this is called — {@link User#getEmail()} is not used as the
     *                  recipient for that reason)
     * @param oldEmail  the address being replaced — also the notification recipient
     * @param newEmail  the newly confirmed address
     * @param changedAt when the change was confirmed
     * @param ip        client IP of the confirmation request (may be {@code null})
     */
    public void notifyEmailChanged(
            final User user, final String oldEmail, final String newEmail, final Instant changedAt, final String ip) {
        emailService.sendEmailChangedNotificationEmail(
            oldEmail, user.getFirstName(), oldEmail, newEmail, changedAt, ip, EmailService.toLocale(user.getLocale()));
    }

    /**
     * Notifies the user that an account-deletion request was confirmed and is now pending —
     * carries the effective purge date and the single-use cancellation link, the only way to
     * abort the deletion once every session has already been revoked.
     *
     * @param user        the account being deleted
     * @param effectiveAt the instant the grace period elapses and anonymization runs
     * @param cancelToken raw single-use cancellation token (SHA-256-hashed in DB)
     * @param ip          client IP of the deletion request (may be {@code null})
     */
    public void notifyAccountDeletionRequested(
            final User user, final Instant effectiveAt, final String cancelToken, final String ip) {
        emailService.sendAccountDeletionConfirmationEmail(
            user.getEmail(), user.getFirstName(), effectiveAt, cancelToken, ip, EmailService.toLocale(user.getLocale()));
    }

    /**
     * Notifies the user that one or more of their active sessions were revoked — a single
     * session signed out from the active-sessions screen, or every other session revoked at
     * once. Always dispatches at most one email regardless of {@code revokedCount}.
     *
     * @param user         the account whose session(s) were revoked
     * @param revokedCount number of sessions revoked by the triggering call — {@code <= 0} is a
     *                     no-op (nothing was actually revoked, nothing to report)
     * @param revokedAt    when the revocation happened
     * @param ip           client IP of the request that triggered the revocation (may be
     *                     {@code null}) — the actor's IP, not the revoked session's original one
     */
    public void notifySessionsRevoked(
            final User user, final int revokedCount, final Instant revokedAt, final String ip) {
        if (revokedCount <= 0) {
            return;
        }
        emailService.sendSessionsRevokedEmail(
            user.getEmail(), user.getFirstName(), revokedCount, revokedAt, ip, EmailService.toLocale(user.getLocale()));
    }
}

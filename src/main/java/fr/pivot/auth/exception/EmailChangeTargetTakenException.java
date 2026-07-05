package fr.pivot.auth.exception;

/**
 * Thrown by {@code GET /account/email/confirm} (US02.2.2) when, at confirmation time, the
 * requested new address turns out to already belong to a different account.
 *
 * <p>This is a narrow race window: the address was free when the confirmation link was
 * issued (checked by {@code EmailChangeService#requestEmailChange}) but was claimed by someone
 * else in the meantime — e.g. that person registered it, or completed their own email-change
 * confirmation to it, before this link was clicked. The confirmation token is consumed
 * regardless (single-use, no retry on the same link); the user must submit a fresh request to
 * try again. Unlike the anti-enumeration 202 on the initiation endpoint, revealing this at
 * confirmation time is not an enumeration risk: the caller already proved control of the
 * target mailbox by receiving and clicking this link.
 */
public class EmailChangeTargetTakenException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public EmailChangeTargetTakenException() {
        super("Email change target address is no longer available");
    }
}

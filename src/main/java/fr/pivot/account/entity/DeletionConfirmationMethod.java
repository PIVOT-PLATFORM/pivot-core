package fr.pivot.account.entity;

/**
 * How an account-deletion request ({@link AccountDeletionRequest}) was confirmed (US02.2.4).
 * Persisted as lowercase string via {@link DeletionConfirmationMethodConverter}.
 */
public enum DeletionConfirmationMethod {
    /** LOCAL accounts — current password verified via {@code PasswordEncoder#matches}. */
    PASSWORD,
    /** OIDC / Google-only accounts (no local password) — 6-digit email OTP, TTL 10 min. */
    OTP
}

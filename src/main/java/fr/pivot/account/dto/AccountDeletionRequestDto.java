package fr.pivot.account.dto;

/**
 * Body of {@code DELETE /account} (US02.2.4) — carries whichever confirmation the account
 * requires, never both, never an identifier of any kind (identity is exclusively the bearer
 * token's resolved user, per the {@code /api/account/*} hard rule).
 *
 * <p>Exactly one field is meaningful for a given account: {@code currentPassword} for LOCAL
 * accounts (a {@code passwordHash} is set), {@code otp} for OIDC / Google-only accounts (no
 * local password) — see {@code AccountDeletionService#confirmationMethod}. The other field is
 * simply ignored; sending the wrong one behaves exactly like sending neither (403).
 *
 * @param currentPassword the account's current password — LOCAL accounts only
 * @param otp             the 6-digit OTP emailed via {@code POST /account/deletion/otp} —
 *                        OIDC/no-local-password accounts only
 */
public record AccountDeletionRequestDto(String currentPassword, String otp) {
}

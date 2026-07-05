-- US02.1.2 — Préférence de langue
--
-- The "preferred language" concept already exists on `users.locale` (introduced in V1,
-- populated from the browser at registration, currently used to localize transactional
-- emails — see EmailService.toLocale / RegistrationService / PasswordService / SessionService).
-- US02.1.2 exposes that same value through the account profile API (as `preferredLanguage`)
-- and makes it user-editable there. Deliberately NOT a new column: `locale` and
-- "preferred language" are the same fact about the user (the language they want PIVOT to
-- address them in) — a second column would create two sources of truth for one concept and
-- risk drift between the UI language and the language of emails sent to the same user.
--
-- `RegisterRequest.locale` is already constrained to fr|en at the application layer
-- (@Pattern "^(fr|en)$"), and no code path other than registration writes this column today
-- (no Google/OIDC claim is ever mapped onto it) — the CHECK below simply promotes that
-- existing invariant to the database, per this schema's convention for enum-like columns
-- (see chk_tenants_plan, chk_at_status, chk_at_auth_method in V1).
ALTER TABLE users
    ADD CONSTRAINT chk_users_locale CHECK (locale IN ('fr', 'en'));

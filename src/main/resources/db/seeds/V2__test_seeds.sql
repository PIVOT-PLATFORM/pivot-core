-- Test seeds — loaded ONLY when spring.flyway.locations includes classpath:db/seeds
-- (application-test.yml). Never loaded in production.
-- All statements are idempotent: ON CONFLICT DO NOTHING / NOT EXISTS guards.
--
-- Password for all test accounts: Pivot@Test123!
-- BCrypt-10 hash: $2b$10$OIQ8qu5fOvZmxqiXVBpIN.5GPuOTs1io4..ChdCfpG8/OcbrPL1We
--
-- Accounts (all on tenant "pivot-saas" id=1):
--   super_admin@pivot.test  ROLE_SUPER_ADMIN, verified
--   admin@pivot.test        ROLE_ADMIN, verified
--   user@pivot.test         ROLE_USER, verified  (id=3 if fresh DB)
--   unverified@pivot.test   ROLE_USER, not verified
--   blocked@pivot.test      ROLE_USER, is_blocked=true

INSERT INTO public.users (tenant_id, email, password_hash, first_name, last_name, role, email_verified, is_active, is_blocked, locale)
VALUES (1, 'super_admin@pivot.test', '$2b$10$OIQ8qu5fOvZmxqiXVBpIN.5GPuOTs1io4..ChdCfpG8/OcbrPL1We', 'Super', 'Admin', 'ROLE_SUPER_ADMIN', TRUE, TRUE, FALSE, 'fr')
ON CONFLICT (tenant_id, email) DO NOTHING;

INSERT INTO public.users (tenant_id, email, password_hash, first_name, last_name, role, email_verified, is_active, is_blocked, locale)
VALUES (1, 'admin@pivot.test', '$2b$10$OIQ8qu5fOvZmxqiXVBpIN.5GPuOTs1io4..ChdCfpG8/OcbrPL1We', 'Admin', 'Pivot', 'ROLE_ADMIN', TRUE, TRUE, FALSE, 'fr')
ON CONFLICT (tenant_id, email) DO NOTHING;

INSERT INTO public.users (tenant_id, email, password_hash, first_name, last_name, role, email_verified, is_active, is_blocked, locale)
VALUES (1, 'user@pivot.test', '$2b$10$OIQ8qu5fOvZmxqiXVBpIN.5GPuOTs1io4..ChdCfpG8/OcbrPL1We', 'Alice', 'Martin', 'ROLE_USER', TRUE, TRUE, FALSE, 'en')
ON CONFLICT (tenant_id, email) DO NOTHING;

INSERT INTO public.users (tenant_id, email, password_hash, first_name, last_name, role, email_verified, is_active, is_blocked, locale)
VALUES (1, 'unverified@pivot.test', '$2b$10$OIQ8qu5fOvZmxqiXVBpIN.5GPuOTs1io4..ChdCfpG8/OcbrPL1We', 'Bob', 'Unverified', 'ROLE_USER', FALSE, TRUE, FALSE, 'fr')
ON CONFLICT (tenant_id, email) DO NOTHING;

INSERT INTO public.users (tenant_id, email, password_hash, first_name, last_name, role, email_verified, is_active, is_blocked, locale)
VALUES (1, 'blocked@pivot.test', '$2b$10$OIQ8qu5fOvZmxqiXVBpIN.5GPuOTs1io4..ChdCfpG8/OcbrPL1We', 'Charlie', 'Blocked', 'ROLE_USER', TRUE, TRUE, TRUE, 'fr')
ON CONFLICT (tenant_id, email) DO NOTHING;

INSERT INTO public.trusted_devices (user_id, device_fingerprint, device_name, ip_address, confirmed_at, last_seen_at, created_at, expires_at)
SELECT u.id, 'test-device-fingerprint-alice-001', 'Chrome / Windows (test)', '127.0.0.1', NOW(), NOW(), NOW(), NOW() + INTERVAL '90 days'
FROM public.users u WHERE u.email = 'user@pivot.test' AND u.tenant_id = 1
AND NOT EXISTS (
    SELECT 1 FROM public.trusted_devices td
    WHERE td.user_id = u.id AND td.device_fingerprint = 'test-device-fingerprint-alice-001'
);

INSERT INTO public.audit_events (user_id, tenant_id, event_type, ip_address, user_agent, meta)
SELECT u.id, 1, 'LOGIN_SUCCESS', '127.0.0.1', 'Mozilla/5.0 (test)', '{"device":"test-device-fingerprint-alice-001"}'::jsonb
FROM public.users u WHERE u.email = 'user@pivot.test' AND u.tenant_id = 1
AND NOT EXISTS (SELECT 1 FROM public.audit_events WHERE event_type = 'LOGIN_SUCCESS' AND user_id = u.id);

INSERT INTO public.audit_events (user_id, tenant_id, event_type, ip_address, user_agent, meta)
SELECT u.id, 1, 'LOGIN_BLOCKED', '127.0.0.1', 'Mozilla/5.0 (test)', '{"reason":"account_blocked"}'::jsonb
FROM public.users u WHERE u.email = 'blocked@pivot.test' AND u.tenant_id = 1
AND NOT EXISTS (SELECT 1 FROM public.audit_events WHERE event_type = 'LOGIN_BLOCKED' AND user_id = u.id);

INSERT INTO public.audit_events (user_id, tenant_id, event_type, ip_address, user_agent)
SELECT u.id, 1, 'LOGIN_UNVERIFIED_EMAIL', '127.0.0.1', 'Mozilla/5.0 (test)'
FROM public.users u WHERE u.email = 'unverified@pivot.test' AND u.tenant_id = 1
AND NOT EXISTS (SELECT 1 FROM public.audit_events WHERE event_type = 'LOGIN_UNVERIFIED_EMAIL' AND user_id = u.id);

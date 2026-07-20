-- V1.1: widen the CHECK constraint on notifications.type to include BOARD_SHARED,
-- BOARD_ROLE_CHANGED and BOARD_ACCESS_REVOKED on databases that applied V1 before those three
-- values were added.
--
-- Why this migration exists: chk_notifications_type is declared in V1__schema_init.sql. Under
-- the "V1 mutable avant la BETA" convention, PR #236 (commit 27ac272) widened that CHECK
-- in-place to add the three BOARD_* values used by fr.pivot.collaboratif.whiteboard.share.
-- BoardInviteService. Flyway keys a migration by version, not content: it never re-runs an
-- applied V1, so the persistent recette-managed Cloud SQL instance kept the OLD (narrow)
-- constraint even after that edit — while its checksum drifted from the one this build resolves,
-- which is the recette outage of 2026-07-20 (Boot's public-schema Flyway `validate` — run
-- implicitly by `migrate` — aborted with FlywayValidateException before the EntityManagerFactory
-- could build).
--
-- Same additive rationale as collaboratif's V8 (commit 0ff5178, #235): editing V1 in place
-- cannot reach an already-migrated persistent DB, so the fix is a forward migration — DROP the
-- named constraint (if it still carries the narrow list) and re-ADD it with the full widened
-- list. `IF EXISTS` on the drop plus re-adding the exact same definition V1 now ships makes this
-- a safe no-op on fresh databases (where V1 already created the wide constraint) and a real
-- widen on legacy ones — both paths converge on the same schema. Kept alongside (not instead of)
-- V1's own wide definition: V1 stays mutable pre-BETA (CLAUDE.md convention) and a fresh DB runs
-- V1 then this; leaving V1 wide costs nothing and keeps the single-file-until-BETA convention
-- readable. This exists solely to reach the one persistent DB (recette) that already applied the
-- old snapshot.
--
-- Why version "1.1" (V1_1) and not "V2": in the TEST profile only (application-test.yml), Boot's
-- public-schema Flyway scans classpath:db/migration/public AND classpath:db/seeds together as one
-- version namespace, and db/seeds/V2__test_seeds.sql already occupies version 2. A "V2" here would
-- collide ("Found more than one migration with version 2"). A dotted patch version sorts strictly
-- between V1 (schema) and V2 (test seeds), so it (a) runs after the schema and before the seed
-- data in tests, and (b) leaves no confusing gap in the production db/migration/public sequence
-- (prod never loads db/seeds — see application.yml locations). On recette (only ever V1 applied)
-- it is simply the next pending version.
ALTER TABLE notifications
    DROP CONSTRAINT IF EXISTS chk_notifications_type;

ALTER TABLE notifications
    ADD CONSTRAINT chk_notifications_type CHECK (
        type IN ('ROLE_CHANGED', 'ACCOUNT_DEACTIVATED', 'SENSITIVE_ACTION', 'UNKNOWN_DEVICE',
                 'BOARD_SHARED', 'BOARD_ROLE_CHANGED', 'BOARD_ACCESS_REVOKED')
    );

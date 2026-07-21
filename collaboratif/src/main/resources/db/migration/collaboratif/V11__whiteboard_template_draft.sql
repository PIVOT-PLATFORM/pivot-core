-- V11: template ownership + template-draft board, for US08.13.2.
--
-- Convention note (see CLAUDE.md, "Migrations Flyway — fichier V1 unique avant la BETA", and the
-- headers of V2..V10 for the precedent this follows): V1..V10 have already been applied against
-- the persistent recette-managed Cloud SQL instance by the continuous-deploy pipeline — Flyway has
-- recorded their checksums, so editing any of them in place would break validation on the next
-- deploy. This file is therefore additive.
--
-- Why this migration exists (US08.13.2):
--
--   1. `whiteboard_template.owner_id` — a template created by "save as template" is currently
--      scoped by `tenant_id` alone, which makes it visible to the whole organisation with no way
--      for its author to say otherwise. US08.13.2 makes templates *personal*; US08.13.5 then adds
--      the explicit sharing (tenant-wide or to named people) that the current implicit behaviour
--      only approximates. Nullable, because the 10 seeded global templates have no author.
--
--   2. `whiteboard_template.updated_at` — `save-from-draft` rewrites a template's content, and the
--      gallery orders personal templates by most-recently-edited. `created_at` alone cannot
--      express that.
--
--   3. `board.template_draft_of` — the throwaway board backing a template's content edition.
--      Deliberately **without a declared foreign key** to `whiteboard_template(id)`: the
--      relationship is resolved in code so that deleting a template never cascades into boards,
--      and so a stale draft can never block a template deletion.
--
-- Ownership FK follows `board.owner_id` (V1, l. 17): a plain `REFERENCES public.users(id)` with no
-- cascade. Deleting a user who still owns templates therefore fails loudly rather than silently
-- destroying content — surfacing the decision instead of taking it.
--
-- Every statement is idempotent (`IF NOT EXISTS`), so re-running this file is a no-op.

ALTER TABLE collaboratif.whiteboard_template
    ADD COLUMN IF NOT EXISTS owner_id   BIGINT      REFERENCES public.users(id),
    ADD COLUMN IF NOT EXISTS updated_at TIMESTAMPTZ NOT NULL DEFAULT now();

-- Drives the gallery's "my templates" lookup, which filters on owner_id.
CREATE INDEX IF NOT EXISTS idx_whiteboard_template_owner_id
    ON collaboratif.whiteboard_template(owner_id);

ALTER TABLE collaboratif.board
    ADD COLUMN IF NOT EXISTS template_draft_of UUID;

-- Composite in this order because every draft lookup is "does *this user* have a draft for *this
-- template*" — `owner_id` is the selective leading column, and the same index also serves the
-- `template_draft_of IS NULL` exclusion applied to every board listing.
CREATE INDEX IF NOT EXISTS idx_board_template_draft_of
    ON collaboratif.board(owner_id, template_draft_of);

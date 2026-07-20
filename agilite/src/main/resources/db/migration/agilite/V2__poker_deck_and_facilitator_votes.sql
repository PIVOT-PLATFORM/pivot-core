-- E09 planning poker — classic parity, increment 1 (deck choice + facilitator-votes).
--
-- Additive FORWARD migration, deliberately NOT folded into the mutable V1: a persistent database
-- (recette) already carries an agilite.poker_rooms table from earlier deploys, and Flyway never
-- re-runs V1 there (its CREATE TABLE IF NOT EXISTS is a no-op on an existing schema), so a V1 edit
-- would never reach recette. Same precedent as collaboratif's V8 (frame table). Fresh
-- environments apply V1 then this, ending in the same state.

-- 1) Allow the three v1 decks. V1 pinned the deck with an inline (unnamed) CHECK, which Postgres
--    auto-named `poker_rooms_sequence_check`; drop it (IF EXISTS: absent on a brand-new schema
--    where V1 and V2 run back-to-back only if V1 shipped the constraint — it did) and replace it
--    with the widened, explicitly-named constraint.
ALTER TABLE agilite.poker_rooms DROP CONSTRAINT IF EXISTS poker_rooms_sequence_check;
ALTER TABLE agilite.poker_rooms
    ADD CONSTRAINT poker_rooms_sequence_check
    CHECK (sequence IN ('FIBONACCI', 'FIBONACCI_SIMPLE', 'TSHIRT'));

-- 2) Whether the facilitator also votes ("le scrum master joue-t-il ?"). Existing rooms keep the
--    previous implicit behaviour (facilitator counted as a regular voter) via DEFAULT TRUE.
ALTER TABLE agilite.poker_rooms
    ADD COLUMN IF NOT EXISTS facilitator_votes BOOLEAN NOT NULL DEFAULT TRUE;

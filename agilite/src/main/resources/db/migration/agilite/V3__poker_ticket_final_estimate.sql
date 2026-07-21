-- US09.2.3 — reset & finalisation de l'estimation d'un ticket de planning poker.
--
-- Additive FORWARD migration, same precedent as V2 (E09 deck/facilitator-votes): a persistent
-- database (recette) already carries agilite.poker_tickets rows from earlier deploys, and Flyway
-- never re-runs V1 there, so a V1 edit would never reach recette. Fresh environments apply V1,
-- then V2, then this one, ending in the same state.

-- Nullable: null means "not finalized yet" (the vast majority of tickets, including every VOTING
-- one). No CHECK against PokerCardDeck values here — enforced at the application layer
-- (PokerTicketService#finalizeEstimate) against the room's own deck, exactly like poker_votes.value.
ALTER TABLE agilite.poker_tickets
    ADD COLUMN IF NOT EXISTS final_estimate VARCHAR(10);

package fr.pivot.agilite.poker.ticket;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * JPA entity backing a planning poker estimation ticket (US09.2.1), table {@code
 * agilite.poker_tickets}.
 *
 * <p>Never exposed directly over the API — {@code PokerTicketController} always returns a {@code
 * TicketResponse} DTO built by {@link PokerTicketService}, per this repo's "no JPA entity in API
 * responses" standard. {@code UUID} primary key, matching {@code fr.pivot.agilite.poker.PokerRoom}
 * and every other room-scoped resource in this module.
 *
 * <p>A room has at most one ticket with {@link PokerTicketStatus#VOTING} at a time — enforced
 * both here (see {@link PokerTicketService#create}) and by a partial unique database index (the
 * stronger, DB-level guarantee), same "structural guarantee over app-only discipline" convention
 * already used by {@code agilite.retro_cards}' anonymity {@code CHECK} constraint (US20.1.1).
 */
@Entity
@Table(name = "poker_tickets", schema = "agilite")
public class PokerTicket {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "room_id", nullable = false)
    private UUID roomId;

    @Column(name = "title", nullable = false, length = 200)
    private String title;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private PokerTicketStatus status;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "revealed_at")
    private Instant revealedAt;

    @Column(name = "final_estimate", length = 10)
    private String finalEstimate;

    /** No-argument constructor required by JPA. */
    protected PokerTicket() {
    }

    /**
     * Creates a new ticket ready to persist, always {@link PokerTicketStatus#VOTING} — no other
     * status is ever settable at creation time.
     *
     * @param roomId    the owning room's identifier
     * @param title     the ticket's display title
     * @param createdAt creation timestamp
     */
    public PokerTicket(final UUID roomId, final String title, final Instant createdAt) {
        this.roomId = roomId;
        this.title = title;
        this.status = PokerTicketStatus.VOTING;
        this.createdAt = createdAt;
    }

    /** @return database primary key */
    public UUID getId() {
        return id;
    }

    /** @return the owning room's identifier */
    public UUID getRoomId() {
        return roomId;
    }

    /** @return the ticket's display title */
    public String getTitle() {
        return title;
    }

    /** @return the ticket's current lifecycle status */
    public PokerTicketStatus getStatus() {
        return status;
    }

    /** @return the creation timestamp */
    public Instant getCreatedAt() {
        return createdAt;
    }

    /** @return the revelation timestamp, or {@code null} while still {@link PokerTicketStatus#VOTING} */
    public Instant getRevealedAt() {
        return revealedAt;
    }

    /**
     * @return the facilitator-validated final estimate (US09.2.3), or {@code null} if this
     *     ticket has not been finalized yet — the vast majority of tickets, including every one
     *     still {@link PokerTicketStatus#VOTING}
     */
    public String getFinalEstimate() {
        return finalEstimate;
    }

    /** @return {@code true} if {@link #finalizeEstimate} has already been called on this ticket */
    public boolean isFinalized() {
        return finalEstimate != null;
    }

    /**
     * Reveals this ticket (US09.2.2): transitions {@link #status} to {@link
     * PokerTicketStatus#REVEALED} and sets {@link #revealedAt}. A one-time transition — the
     * caller (see {@code PokerTicketService#reveal}) must guard against calling this on a ticket
     * that is already {@link PokerTicketStatus#REVEALED}.
     *
     * @param revealedAt the revelation timestamp
     */
    public void reveal(final Instant revealedAt) {
        this.status = PokerTicketStatus.REVEALED;
        this.revealedAt = revealedAt;
    }

    /**
     * Resets this ticket (US09.2.3): transitions {@link #status} back to {@link
     * PokerTicketStatus#VOTING} and clears {@link #revealedAt} — a new round of voting, callable
     * any number of times on the same ticket. The caller (see {@code PokerTicketService#reset})
     * must guard against calling this on a ticket that is not currently {@link
     * PokerTicketStatus#REVEALED}, or already {@link #isFinalized() finalized} (terminal state),
     * and is responsible for deleting the previous round's cast votes.
     */
    public void reset() {
        this.status = PokerTicketStatus.VOTING;
        this.revealedAt = null;
    }

    /**
     * Persists the facilitator-validated final estimate (US09.2.3) — a terminal, one-time
     * transition; once set, neither {@link #reset()} nor a second call to this method may be
     * applied to this ticket again (enforced by the caller, see {@code
     * PokerTicketService#finalizeEstimate}). Named to avoid colliding with {@link Object#finalize()}.
     *
     * @param finalEstimate the facilitator-chosen final estimate, one of the room's own deck
     *                      values
     */
    public void finalizeEstimate(final String finalEstimate) {
        this.finalEstimate = finalEstimate;
    }
}

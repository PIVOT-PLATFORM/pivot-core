package fr.pivot.collaboratif.session.vote;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.util.UUID;

/**
 * One row per VOTE-type session, tracking whether the facilitator has closed the vote (US19.3.6).
 * Absent row = still open (default); tallies are only revealed once {@code closed} is {@code true}.
 */
@Entity
@Table(name = "session_vote_state", schema = "collaboratif")
public class SessionVoteState {

    /** The owning session's id, and this row's primary key. */
    @Id
    @Column(name = "session_id")
    private UUID sessionId;

    /** Whether the facilitator has closed the vote (tallies revealed). */
    @Column(nullable = false)
    private boolean closed;

    /** No-arg constructor required by JPA. */
    protected SessionVoteState() {
    }

    /**
     * Creates a state row.
     *
     * @param sessionId the owning session's UUID
     * @param closed    whether the vote is closed
     */
    public SessionVoteState(final UUID sessionId, final boolean closed) {
        this.sessionId = sessionId;
        this.closed = closed;
    }

    public UUID getSessionId() {
        return sessionId;
    }

    public boolean isClosed() {
        return closed;
    }

    public void setClosed(final boolean closed) {
        this.closed = closed;
    }
}

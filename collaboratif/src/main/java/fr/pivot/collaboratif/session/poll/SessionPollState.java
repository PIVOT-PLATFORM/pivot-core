package fr.pivot.collaboratif.session.poll;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.util.UUID;

/**
 * Whether a POLL-type session's results are currently hidden from participants (US19.3.2,
 * {@code hide-results}/{@code show-results}). Absent row = visible (default); a facilitator's
 * {@code hide-results} call creates/updates this row to {@code true}.
 */
@Entity
@Table(name = "session_poll_state", schema = "collaboratif")
public class SessionPollState {

    /** Primary key — the owning session's id, one row per POLL session at most. */
    @Id
    @Column(name = "session_id")
    private UUID sessionId;

    /** Whether results are currently withheld from participants. */
    @Column(name = "results_hidden", nullable = false)
    private boolean resultsHidden;

    /** No-arg constructor required by JPA. */
    protected SessionPollState() {
    }

    /**
     * Creates the state row for a session.
     *
     * @param sessionId     the owning session's UUID
     * @param resultsHidden the initial hidden state
     */
    public SessionPollState(final UUID sessionId, final boolean resultsHidden) {
        this.sessionId = sessionId;
        this.resultsHidden = resultsHidden;
    }

    /**
     * Returns the owning session's identifier.
     *
     * @return the session UUID
     */
    public UUID getSessionId() {
        return sessionId;
    }

    /**
     * Returns whether results are currently hidden.
     *
     * @return {@code true} if withheld from participants
     */
    public boolean isResultsHidden() {
        return resultsHidden;
    }

    /**
     * Sets whether results are currently hidden.
     *
     * @param resultsHidden the new hidden state
     */
    public void setResultsHidden(final boolean resultsHidden) {
        this.resultsHidden = resultsHidden;
    }
}

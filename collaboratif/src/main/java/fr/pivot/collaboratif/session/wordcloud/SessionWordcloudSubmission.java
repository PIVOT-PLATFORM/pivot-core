package fr.pivot.collaboratif.session.wordcloud;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * Tracks one participant's individual word submission in a WORDCLOUD activity (US19.3.3) — used
 * to enforce {@code config.maxWordsPerParticipant}, independent of the aggregated
 * {@link SessionWordcloudEntry} frequency.
 */
@Entity
@Table(name = "session_wordcloud_submission", schema = "collaboratif")
public class SessionWordcloudSubmission {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "session_id", nullable = false, updatable = false)
    private UUID sessionId;

    @Column(name = "participant_id", nullable = false, updatable = false)
    private UUID participantId;

    @Column(nullable = false, updatable = false, length = 30)
    private String word;

    @Column(name = "submitted_at", nullable = false, updatable = false)
    private Instant submittedAt;

    protected SessionWordcloudSubmission() {
    }

    /**
     * Creates a new submission record.
     *
     * @param sessionId     the owning session's UUID
     * @param participantId the submitting participant's id
     * @param word          the normalized word
     * @param submittedAt   submission timestamp
     */
    public SessionWordcloudSubmission(
            final UUID sessionId, final UUID participantId, final String word, final Instant submittedAt) {
        this.sessionId = sessionId;
        this.participantId = participantId;
        this.word = word;
        this.submittedAt = submittedAt;
    }

    public UUID getId() {
        return id;
    }

    public UUID getSessionId() {
        return sessionId;
    }

    public UUID getParticipantId() {
        return participantId;
    }

    public String getWord() {
        return word;
    }

    public Instant getSubmittedAt() {
        return submittedAt;
    }
}

package fr.pivot.collaboratif.session.wordcloud;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.util.UUID;

/**
 * An aggregated, normalized word in a WORDCLOUD activity (US19.3.3) — one row per distinct word
 * per session, with a running submission frequency.
 */
@Entity
@Table(name = "session_wordcloud_entry", schema = "collaboratif")
public class SessionWordcloudEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "session_id", nullable = false, updatable = false)
    private UUID sessionId;

    @Column(nullable = false, updatable = false, length = 30)
    private String word;

    @Column(nullable = false)
    private int frequency;

    protected SessionWordcloudEntry() {
    }

    /**
     * Creates a new entry with an initial frequency of 1.
     *
     * @param sessionId the owning session's UUID
     * @param word      the normalized word
     */
    public SessionWordcloudEntry(final UUID sessionId, final String word) {
        this.sessionId = sessionId;
        this.word = word;
        this.frequency = 1;
    }

    /** Increments the submission frequency by one. */
    public void incrementFrequency() {
        this.frequency++;
    }

    public UUID getId() {
        return id;
    }

    public UUID getSessionId() {
        return sessionId;
    }

    public String getWord() {
        return word;
    }

    public int getFrequency() {
        return frequency;
    }
}

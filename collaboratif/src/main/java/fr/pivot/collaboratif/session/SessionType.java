package fr.pivot.collaboratif.session;

/**
 * The six live activity types a {@link Session} can be created with (US19.1.1).
 *
 * <p>Fixed at session creation — this socle has no multi-activity sequence within one session.
 */
public enum SessionType {
    QUIZ,
    POLL,
    WORDCLOUD,
    BRAINSTORM,
    QA,
    VOTE
}

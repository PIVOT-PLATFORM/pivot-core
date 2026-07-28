package fr.pivot.collaboratif.session;

/**
 * The live activity types a {@link Session} can be created with (US19.1.1).
 *
 * <p>Fixed at session creation — this socle has no multi-activity sequence within one session.
 *
 * <p>{@code POSTIT_RUSH} (US47.2.1, E47/F47.2) reuses this exact shared session/join/participant
 * socle — deliberately aligned to the E19 canonical room pattern rather than a bespoke room/join
 * model, per the US47.2.1 PO Agent note to align to an existing canonical path when one exists.
 */
public enum SessionType {
    QUIZ,
    POLL,
    WORDCLOUD,
    BRAINSTORM,
    QA,
    VOTE,
    POSTIT_RUSH
}

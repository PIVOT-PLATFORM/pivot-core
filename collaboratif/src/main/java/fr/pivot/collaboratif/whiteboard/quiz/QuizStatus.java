package fr.pivot.collaboratif.whiteboard.quiz;

/**
 * Lifecycle status of a {@link QuizSession}.
 *
 * <p>A session is created {@link #ACTIVE} on {@code quiz:start} and transitions once, terminally,
 * to {@link #CLOSED} on {@code quiz:stop}. There is no reopening — a new quiz is a new session.
 * The wire form is the enum name itself ({@code "ACTIVE"}/{@code "CLOSED"}), matching the
 * frontend's {@code QuizSession.status} union type exactly.
 */
public enum QuizStatus {

    /** The session accepts answers and facilitator progression. At most one ACTIVE session
     * exists per board. */
    ACTIVE,

    /** The session is terminated; no further answers or progression are accepted. */
    CLOSED
}

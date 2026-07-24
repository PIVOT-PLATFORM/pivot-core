package fr.pivot.collaboratif.session.quiz.dto;

import java.time.Instant;
import java.util.List;

/**
 * The participant-safe reconnect snapshot of a QUIZ (US19.3.1) — everything a client needs to
 * rejoin mid-quiz: the current question (its correct answer withheld until ended), the caller's own
 * score and whether they already answered it, and, once the question has ended, the revealed
 * correct indices and the leaderboard.
 *
 * @param started         whether the quiz has begun (any question shown yet)
 * @param currentQuestionIndex the live question index, or {@code -1}
 * @param totalQuestions  the number of questions in the quiz
 * @param questionText    the current question text, or {@code null} if not started
 * @param options         the current question's options, or empty
 * @param durationSeconds the current question's answer window, or {@code null}
 * @param questionStartedAt when the current question started (the client derives its countdown)
 * @param questionEnded   whether the current question has ended
 * @param hasAnswered     whether the caller already answered the current question
 * @param myScore         the caller's cumulative score
 * @param correctIndices  the current question's correct indices — only once ended, else empty
 * @param leaderboard     the current leaderboard — only once a question has ended, else empty
 */
public record QuizStateDto(
        boolean started,
        int currentQuestionIndex,
        int totalQuestions,
        String questionText,
        List<String> options,
        Integer durationSeconds,
        Instant questionStartedAt,
        boolean questionEnded,
        boolean hasAnswered,
        int myScore,
        List<Integer> correctIndices,
        List<LeaderboardEntry> leaderboard) {
}

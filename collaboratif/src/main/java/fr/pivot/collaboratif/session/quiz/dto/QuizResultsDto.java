package fr.pivot.collaboratif.session.quiz.dto;

import java.util.List;

/**
 * The final results of a QUIZ (US19.3.1) — the complete ranking plus each question's correct-rate.
 *
 * @param leaderboard              the final ranking, highest score first
 * @param correctRatePerQuestion   per-question ratio of correct answers to total answers (0.0-1.0),
 *                                 indexed by question
 */
public record QuizResultsDto(
        List<LeaderboardEntry> leaderboard,
        List<Double> correctRatePerQuestion) {
}

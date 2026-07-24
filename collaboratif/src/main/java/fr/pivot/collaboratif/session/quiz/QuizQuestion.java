package fr.pivot.collaboratif.session.quiz;

import java.util.List;
import java.util.Set;

/**
 * A single quiz question parsed from a session's {@code config} (US19.3.1) — an in-memory value,
 * not a persisted entity (the question bank lives in the session config; only answers and
 * progression are persisted).
 *
 * @param text            the question text
 * @param options         the answer options
 * @param correctIndices  the set of correct option indices (one for single-answer, N for multi)
 * @param durationSeconds the answer window in seconds
 */
public record QuizQuestion(String text, List<String> options, Set<Integer> correctIndices, int durationSeconds) {
}

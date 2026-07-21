package fr.pivot.collaboratif.whiteboard.quiz.dto;

import fr.pivot.collaboratif.whiteboard.quiz.Answer;

/**
 * Wire representation of a single {@link Answer} (calques {@code VoteResponse}).
 *
 * <p>Field names and types mirror the frontend's expected shape: every id and the timestamp are
 * strings. Deliberately not the JPA entity itself — entities are never serialised directly to
 * clients (see PIVOT-CORE {@code CLAUDE.md}, "Entités JPA exposées directement en API").
 *
 * @param id         the answer's UUID as a string
 * @param sessionId  the owning session's UUID as a string
 * @param questionId the targeted question's UUID as a string
 * @param choiceId   the selected choice's UUID as a string
 * @param userId     the answering user's {@code public.users.id} as a string
 * @param createdAt  the answer's creation instant, ISO-8601
 */
public record AnswerResponse(
        String id, String sessionId, String questionId, String choiceId, String userId, String createdAt) {

    /**
     * Builds an {@link AnswerResponse} from a persisted answer.
     *
     * @param answer the persisted answer
     * @return the corresponding {@link AnswerResponse}
     */
    public static AnswerResponse of(final Answer answer) {
        return new AnswerResponse(
                answer.getId().toString(),
                answer.getSessionId().toString(),
                answer.getQuestionId().toString(),
                answer.getChoiceId().toString(),
                String.valueOf(answer.getUserId()),
                answer.getCreatedAt().toString());
    }
}

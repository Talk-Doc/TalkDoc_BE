package com.talkdoc.backend.question.dto;

import com.talkdoc.backend.question.Intent;
import com.talkdoc.backend.question.PendingQuestion;

import java.time.Instant;
import java.util.List;

/**
 * Response body for {@code POST /api/sessions/{sessionId}/question} and
 * {@code PATCH /api/sessions/{sessionId}/questions/{questionId}}. Serialized snake_case
 * (global Jackson naming strategy): {@code question_id, text, intent, intents, candidates,
 * supported, asked_at, version, updated_at}.
 *
 * @param intent    the primary (first) intent, for clients that only care about one
 * @param version   1 when first posted, incremented on every doctor edit; must be echoed back when
 *                  previewing/confirming an answer or editing the question again
 * @param updatedAt time of the last edit, omitted when the question was never edited
 */
public record QuestionResponse(
        String questionId,
        String text,
        Intent intent,
        List<Intent> intents,
        List<String> candidates,
        boolean supported,
        Instant askedAt,
        int version,
        Instant updatedAt
) {

    public static QuestionResponse of(PendingQuestion question) {
        return new QuestionResponse(
                question.questionId(),
                question.text(),
                question.primaryIntent(),
                question.intents(),
                question.candidates(),
                question.isSupported(),
                question.askedAt(),
                question.version(),
                question.updatedAt()
        );
    }
}

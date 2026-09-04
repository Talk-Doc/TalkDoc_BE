package com.talkdoc.backend.question.dto;

import com.talkdoc.backend.question.Intent;
import com.talkdoc.backend.question.PendingQuestion;

import java.time.Instant;
import java.util.List;

/**
 * Response body for {@code POST /api/sessions/{sessionId}/question}. Serialized snake_case
 * (global Jackson naming strategy): {@code question_id, text, intent, intents, candidates,
 * supported, asked_at}.
 *
 * @param intent the primary (first) intent, for clients that only care about one
 */
public record QuestionResponse(
        String questionId,
        String text,
        Intent intent,
        List<Intent> intents,
        List<String> candidates,
        boolean supported,
        Instant askedAt
) {

    public static QuestionResponse of(PendingQuestion question) {
        return new QuestionResponse(
                question.questionId(),
                question.text(),
                question.primaryIntent(),
                question.intents(),
                question.candidates(),
                question.isSupported(),
                question.askedAt()
        );
    }
}

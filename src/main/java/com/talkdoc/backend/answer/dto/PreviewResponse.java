package com.talkdoc.backend.answer.dto;

import java.util.List;

/**
 * Response body for {@code POST /api/sessions/{sessionId}/answer/preview}. Serialized snake_case:
 * {@code question_id, labels, answer}. Not stored.
 */
public record PreviewResponse(
        String questionId,
        List<String> labels,
        String answer
) {
}

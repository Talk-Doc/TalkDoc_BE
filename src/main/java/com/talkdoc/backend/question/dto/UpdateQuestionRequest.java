package com.talkdoc.backend.question.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * Request body for {@code PATCH /api/sessions/{sessionId}/questions/{questionId}}:
 * {@code {"text": "...", "version": 1}}.
 *
 * <p>{@code version} is the version the doctor last saw; the edit is rejected with 409
 * VERSION_CONFLICT when the stored question has moved on since.</p>
 */
public record UpdateQuestionRequest(
        @NotBlank String text,
        @NotNull Integer version
) {
}

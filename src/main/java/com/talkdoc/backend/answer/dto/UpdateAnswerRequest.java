package com.talkdoc.backend.answer.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * Request body for {@code PATCH /api/sessions/{sessionId}/answer/{answerId}}:
 * {@code {"answer": "...", "version": 1}}.
 *
 * @param version optional guard: the conversation version the client last saw; a mismatch is
 *                rejected with 409 VERSION_CONFLICT
 */
public record UpdateAnswerRequest(
        @NotBlank String answer,
        Integer version
) {
}

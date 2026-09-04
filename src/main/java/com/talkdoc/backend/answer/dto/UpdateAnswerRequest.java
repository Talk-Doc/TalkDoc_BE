package com.talkdoc.backend.answer.dto;

import jakarta.validation.constraints.NotBlank;

/** Request body for {@code PATCH /api/sessions/{sessionId}/answer/{answerId}}: {@code {"answer": "..."}}. */
public record UpdateAnswerRequest(
        @NotBlank String answer
) {
}

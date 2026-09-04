package com.talkdoc.backend.answer.dto;

import jakarta.validation.constraints.NotEmpty;

import java.util.List;

/**
 * Request body for {@code POST /api/sessions/{sessionId}/answer/confirm}:
 * {@code {"labels": [...], "answer": "..."}}. {@code answer} is optional; when blank it is
 * composed the same way as the preview endpoint.
 */
public record ConfirmRequest(
        @NotEmpty List<String> labels,
        String answer
) {
}

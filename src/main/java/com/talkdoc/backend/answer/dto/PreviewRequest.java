package com.talkdoc.backend.answer.dto;

import jakarta.validation.constraints.NotEmpty;

import java.util.List;

/** Request body for {@code POST /api/sessions/{sessionId}/answer/preview}: {@code {"labels": [...]}}. */
public record PreviewRequest(
        @NotEmpty List<String> labels
) {
}

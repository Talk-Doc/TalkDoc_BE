package com.talkdoc.backend.answer.dto;

import java.util.List;

/**
 * Request body for {@code POST /api/sessions/{sessionId}/answer/confirm}:
 * {@code {"labels": [...], "answer": "..."}}.
 *
 * <p>At least one of the two must be present: with labels only, the answer is composed the same
 * way as the preview endpoint; with a non-blank {@code answer} the labels may be empty (patient typed
 * the answer instead of signing). Both present means "store this exact sentence for these signs".
 */
public record ConfirmRequest(
        List<String> labels,
        String answer
) {

    public List<String> labelsOrEmpty() {
        return labels == null ? List.of() : labels;
    }
}

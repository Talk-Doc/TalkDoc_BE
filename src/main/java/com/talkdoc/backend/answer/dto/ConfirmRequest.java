package com.talkdoc.backend.answer.dto;

import java.util.List;

/**
 * Request body for {@code POST /api/sessions/{sessionId}/answer/confirm}.
 *
 * <p>Legacy shape {@code {"labels": [...], "answer": "..."}}: at least one of the two must be present.
 * With labels only, the answer is composed the same way as the preview endpoint; with a non-blank
 * {@code answer} the labels may be empty (patient typed the answer instead of signing).</p>
 *
 * <p>Draft shape {@code {"answer_id": "...", "version": 1}}: confirms the draft created by a preview.
 * {@code answer} may still be sent to override the drafted sentence. Repeating the call with the same
 * {@code answer_id} is idempotent (200 with the already-confirmed conversation instead of 201).</p>
 *
 * @param answerId id of a draft returned by /answer/preview
 * @param version  optional guard: the draft version the client last saw
 */
public record ConfirmRequest(
        List<String> labels,
        String answer,
        String answerId,
        Integer version
) {

    public List<String> labelsOrEmpty() {
        return labels == null ? List.of() : labels;
    }

    public boolean hasAnswerId() {
        return answerId != null && !answerId.isBlank();
    }
}

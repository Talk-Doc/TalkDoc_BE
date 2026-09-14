package com.talkdoc.backend.answer.dto;

import java.util.List;

/**
 * Request body for {@code POST /api/sessions/{sessionId}/answer/preview}.
 *
 * <p>Legacy shape {@code {"labels": [...]}} still works. The versioned shape is
 * {@code {"question_id": "...", "question_version": 2, "recognition_ids": [...]}}: the labels are then
 * taken from the referenced recognitions, in the order given. When both are present
 * {@code recognition_ids} wins. At least one of the two must be non-empty.</p>
 *
 * @param questionId      optional guard: must be the session's currently pending question
 * @param questionVersion optional guard: must be that question's current version
 */
public record PreviewRequest(
        List<String> labels,
        String questionId,
        Integer questionVersion,
        List<String> recognitionIds
) {

    public List<String> labelsOrEmpty() {
        return labels == null ? List.of() : labels;
    }

    public List<String> recognitionIdsOrEmpty() {
        return recognitionIds == null ? List.of() : recognitionIds;
    }
}

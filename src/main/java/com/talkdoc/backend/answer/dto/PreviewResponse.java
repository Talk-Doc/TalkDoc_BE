package com.talkdoc.backend.answer.dto;

import java.util.List;

/**
 * Response body for {@code POST /api/sessions/{sessionId}/answer/preview}. Serialized snake_case:
 * {@code question_id, question_version, labels, answer, answer_id, version, recognition_ids}.
 *
 * <p>The preview is stored as an answer draft, so {@code answer_id} can be sent back to
 * {@code /answer/confirm} instead of the labels; confirming by id is idempotent and is rejected
 * when the doctor has edited the question in the meantime.</p>
 *
 * @param answerId       id of the stored draft, and of the conversation it becomes once confirmed
 * @param version        the draft's version (1 today), echoed back to /answer/confirm as {@code version}
 * @param recognitionIds the recognitions the labels came from, omitted when raw labels were sent
 */
public record PreviewResponse(
        String questionId,
        int questionVersion,
        List<String> labels,
        String answer,
        String answerId,
        int version,
        List<String> recognitionIds
) {
}

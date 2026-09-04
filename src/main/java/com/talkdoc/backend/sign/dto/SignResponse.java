package com.talkdoc.backend.sign.dto;

import com.talkdoc.backend.question.Intent;
import com.talkdoc.backend.sign.RecognizedSign;

import java.util.List;

/**
 * Response body for {@code POST /api/sessions/{sessionId}/sign}. Serialized snake_case
 * (global Jackson naming strategy): {@code question_id, intents, candidates, signs, all_accepted,
 * accepted_labels}. Nothing from this response is persisted.
 *
 * @param questionId    the session's currently pending question id, or null if there is none
 * @param signs         one entry per recognised segment, with the confidence threshold already applied
 * @param allAccepted   true only when signs is non-empty and every entry was accepted
 * @param acceptedLabels the accepted labels, in signing order
 */
public record SignResponse(
        String questionId,
        List<Intent> intents,
        List<String> candidates,
        List<RecognizedSign> signs,
        boolean allAccepted,
        List<String> acceptedLabels
) {
}

package com.talkdoc.backend.sign.dto;

import com.talkdoc.backend.question.Intent;
import com.talkdoc.backend.sign.RecognizedSign;

import java.util.List;

/**
 * Response body for {@code POST /api/sessions/{sessionId}/sign}. Serialized snake_case
 * (global Jackson naming strategy, nulls omitted): {@code question_id, question_version,
 * recognition_id, intents, candidates, sign, signs, all_accepted, accepted_labels, model_version,
 * request_id, processing_ms}.
 *
 * <p>TalkDoc-VisionAI returns one word per video, so there is at most one recognised sign per call.</p>
 *
 * @param questionId      the session's currently pending question id, or null if there is none
 * @param questionVersion the version of that question, or null if there is none
 * @param recognitionId   id of the stored recognition, usable in {@code /answer/preview}'s
 *                        {@code recognition_ids}. Null when nothing was recognised or when the call
 *                        used an explicit intent with no pending question (nothing is stored then).
 * @param intents         intents used for this call (the pending question's, or the explicit intent param)
 * @param candidates      the vocabulary of those intents; informational only — the AI is NOT limited to it
 * @param sign            the recognised sign; never null, but its label may be null when nothing was recognised
 * @param signs           kept for frontend compatibility: [sign] when a label was recognised, otherwise empty
 * @param allAccepted     true only when a label was recognised and accepted
 * @param acceptedLabels  the accepted label as a single-element list, or empty
 * @param modelVersion    AI model version, e.g. "bigru-v2-seed17"
 * @param requestId       the X-Request-ID used for the AI call (echoed by the AI), for log correlation
 * @param processingMs    AI-side inference time in milliseconds
 */
public record SignResponse(
        String questionId,
        Integer questionVersion,
        String recognitionId,
        List<Intent> intents,
        List<String> candidates,
        RecognizedSign sign,
        List<RecognizedSign> signs,
        boolean allAccepted,
        List<String> acceptedLabels,
        String modelVersion,
        String requestId,
        Long processingMs
) {
}

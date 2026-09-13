package com.talkdoc.backend.sign.dto;

import com.talkdoc.backend.question.Intent;
import com.talkdoc.backend.sign.RecognizedSign;

import java.util.List;

/**
 * Response body for {@code POST /api/sessions/{sessionId}/sign}. Serialized snake_case
 * (global Jackson naming strategy, nulls omitted): {@code question_id, intents, candidates, sign,
 * signs, all_accepted, accepted_labels, model_version, request_id, processing_ms}.
 * Nothing from this response is persisted — the frontend collects accepted labels across calls
 * and later submits them to /answer/preview or /answer/confirm.
 *
 * <p>TalkDoc-VisionAI returns one word per video, so there is at most one recognised sign per call.</p>
 *
 * @param questionId     the session's currently pending question id, or null if there is none
 * @param intents        intents used for this call (the pending question's, or the explicit intent param)
 * @param candidates     the vocabulary of those intents; informational only — the AI is NOT limited to it
 * @param sign           the recognised sign; never null, but its label may be null when nothing was recognised
 * @param signs          kept for frontend compatibility: [sign] when a label was recognised, otherwise empty
 * @param allAccepted    true only when a label was recognised and accepted
 * @param acceptedLabels the accepted label as a single-element list, or empty
 * @param modelVersion   AI model version, e.g. "bigru-v2-seed17"
 * @param requestId      the X-Request-ID used for the AI call (echoed by the AI), for log correlation
 * @param processingMs   AI-side inference time in milliseconds
 */
public record SignResponse(
        String questionId,
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

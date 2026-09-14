package com.talkdoc.backend.ai;

import com.talkdoc.backend.ai.model.SignPrediction;

/**
 * Bridge to the TalkDoc-VisionAI service (separate Flask repo, {@code POST /predict} on port 5001).
 * See docs/ai-service-contract.md.
 * Implementations: MockSignRecognitionClient (talkdoc.sign-ai.mode=mock), HttpSignRecognitionClient (mode=http).
 * Must throw ApiException(ErrorCode.SIGN_AI_FAILED) on transport/provider errors and
 * ApiException(ErrorCode.SIGN_VIDEO_REJECTED) when the service could not decode/accept the video.
 */
public interface SignRecognitionClient {

    /**
     * Recognises exactly one sign word from one video. The service is not given the question's
     * candidate list: candidates must not force-limit the prediction.
     *
     * @param video           raw bytes as uploaded; callers discard them right after this call
     * @param mimeType        e.g. "video/webm", "video/mp4" (parameters are ignored by the HTTP client,
     *                        but the mock uses them as a debug override)
     * @param durationSeconds actual recording length in seconds, or null if unknown (0 &lt; d &le; 20)
     * @return the prediction; never null, but {@link SignPrediction#label()} may be null
     */
    SignPrediction predict(byte[] video, String mimeType, Double durationSeconds);
}

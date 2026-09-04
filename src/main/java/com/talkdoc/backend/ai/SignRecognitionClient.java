package com.talkdoc.backend.ai;

import com.talkdoc.backend.ai.model.SignResult;
import com.talkdoc.backend.question.Intent;

import java.util.List;

/**
 * Bridge to the TalkDoc_AI service (separate Python repo). See docs/ai-service-contract.md.
 * Implementations: MockSignRecognitionClient (talkdoc.sign-ai.mode=mock), HttpSignRecognitionClient (mode=http).
 * Must throw ApiException(ErrorCode.SIGN_AI_FAILED) on transport/provider errors.
 */
public interface SignRecognitionClient {

    /**
     * @param video      raw bytes as uploaded; callers discard them right after this call
     * @param mimeType   e.g. "video/webm", "video/mp4"
     * @param intents    intents of the current question (context for candidate restriction)
     * @param candidates allowed labels; the AI must only return labels from this list
     * @return one result per detected segment, in signing order; empty list if nothing detected
     */
    List<SignResult> recognize(byte[] video, String mimeType, List<Intent> intents, List<String> candidates);
}

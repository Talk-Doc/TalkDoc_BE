package com.talkdoc.backend.ai;

/**
 * Speech-to-text for the doctor's question audio.
 * Implementations: MockSttClient (default), GeminiSttClient (talkdoc.ai.provider=gemini).
 * Must throw ApiException(ErrorCode.STT_FAILED) on provider errors.
 */
public interface SttClient {

    /**
     * @param audio    raw bytes as uploaded (never persisted by callers)
     * @param mimeType e.g. "audio/webm", "audio/wav", "audio/mpeg"
     * @return transcript in Korean, trimmed; empty string if nothing was recognised
     */
    String transcribe(byte[] audio, String mimeType);
}

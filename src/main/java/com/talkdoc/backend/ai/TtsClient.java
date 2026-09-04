package com.talkdoc.backend.ai;

import com.talkdoc.backend.ai.model.TtsAudio;

/**
 * Text-to-speech for reading the patient's answer to the doctor (FR-16, priority 3).
 * Implementations: MockTtsClient (default), GeminiTtsClient.
 * Must throw ApiException(ErrorCode.TTS_FAILED) on provider errors.
 */
public interface TtsClient {

    TtsAudio synthesize(String text);
}

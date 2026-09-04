package com.talkdoc.backend.ai.mock;

import com.talkdoc.backend.ai.TtsClient;
import com.talkdoc.backend.ai.gemini.WavEncoder;
import com.talkdoc.backend.ai.model.TtsAudio;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** Returns half a second of silence as a valid WAV so clients can exercise the audio path. */
@Component
@ConditionalOnProperty(prefix = "talkdoc.ai", name = "provider", havingValue = "mock", matchIfMissing = true)
public class MockTtsClient implements TtsClient {

    private static final int SAMPLE_RATE = 8_000;

    @Override
    public TtsAudio synthesize(String text) {
        byte[] pcm = new byte[SAMPLE_RATE]; // 0.5 s * 8000 Hz * 2 bytes = 8000 bytes of silence
        return new TtsAudio(WavEncoder.pcm16ToWav(pcm, SAMPLE_RATE, 1), "audio/wav");
    }
}

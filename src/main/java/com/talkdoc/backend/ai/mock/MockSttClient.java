package com.talkdoc.backend.ai.mock;

import com.talkdoc.backend.ai.SttClient;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

/**
 * Default STT when no provider key is configured. Returns a fixed demo question, or, if the uploaded
 * bytes are short plain UTF-8 text, that text (so tests can upload a .txt as "audio").
 */
@Component
@ConditionalOnProperty(prefix = "talkdoc.ai", name = "provider", havingValue = "mock", matchIfMissing = true)
public class MockSttClient implements SttClient {

    public static final String DEFAULT_TRANSCRIPT = "어디가 아파서 오셨어요?";

    @Override
    public String transcribe(byte[] audio, String mimeType) {
        if (audio == null || audio.length == 0) {
            return "";
        }
        if (audio.length < 200 && looksLikeText(audio)) {
            return new String(audio, StandardCharsets.UTF_8).strip();
        }
        return DEFAULT_TRANSCRIPT;
    }

    private static boolean looksLikeText(byte[] bytes) {
        for (byte b : bytes) {
            int v = b & 0xFF;
            if (v < 0x20 && v != '\n' && v != '\r' && v != '\t') {
                return false;
            }
        }
        String s = new String(bytes, StandardCharsets.UTF_8);
        return !s.contains("�") && !s.isBlank();
    }
}

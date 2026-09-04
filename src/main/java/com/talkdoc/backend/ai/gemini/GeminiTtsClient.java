package com.talkdoc.backend.ai.gemini;

import com.talkdoc.backend.ai.TtsClient;
import com.talkdoc.backend.ai.model.TtsAudio;
import com.talkdoc.backend.common.ApiException;
import com.talkdoc.backend.common.ErrorCode;
import com.talkdoc.backend.config.TalkDocProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Gemini TTS. The API returns raw 16-bit PCM (audio/L16;codec=pcm;rate=24000) which is wrapped
 * into a WAV container so browsers can play it directly.
 */
@Component
@ConditionalOnProperty(prefix = "talkdoc.ai", name = "provider", havingValue = "gemini")
public class GeminiTtsClient implements TtsClient {

    static final String VOICE = "Kore";
    private static final Pattern RATE = Pattern.compile("rate=(\\d+)");

    private final GeminiClient client;
    private final String model;

    public GeminiTtsClient(GeminiClient client, TalkDocProperties properties) {
        this.client = client;
        this.model = properties.ai().gemini().models().tts();
    }

    @Override
    public TtsAudio synthesize(String text) {
        if (text == null || text.isBlank()) {
            throw new ApiException(ErrorCode.TTS_FAILED, "Nothing to synthesize");
        }
        Map<String, Object> body = GeminiClient.request(
                List.of(GeminiClient.textPart(text)),
                Map.of("responseModalities", List.of("AUDIO"),
                        "speechConfig", Map.of("voiceConfig",
                                Map.of("prebuiltVoiceConfig", Map.of("voiceName", VOICE)))));
        GeminiResponse response = client.generateContent(model, body, ErrorCode.TTS_FAILED);
        byte[] pcm = response.firstInlineData();
        if (pcm == null || pcm.length == 0) {
            throw new ApiException(ErrorCode.TTS_FAILED, "Gemini returned no audio");
        }
        int sampleRate = sampleRateOf(response.firstInlineMimeType());
        return new TtsAudio(WavEncoder.pcm16ToWav(pcm, sampleRate, 1), "audio/wav");
    }

    static int sampleRateOf(String mimeType) {
        if (mimeType != null) {
            Matcher m = RATE.matcher(mimeType);
            if (m.find()) return Integer.parseInt(m.group(1));
        }
        return 24_000;
    }
}

package com.talkdoc.backend.ai.gemini;

import com.talkdoc.backend.ai.SttClient;
import com.talkdoc.backend.common.ApiException;
import com.talkdoc.backend.common.ErrorCode;
import com.talkdoc.backend.config.TalkDocProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/** Speech-to-text via Gemini audio understanding (inlineData + transcription instruction). */
@Component
@ConditionalOnProperty(prefix = "talkdoc.ai", name = "provider", havingValue = "gemini")
public class GeminiSttClient implements SttClient {

    static final String PROMPT = "이 오디오의 한국어 발화를 그대로 받아쓰기 하세요. 받아쓴 문장만 출력하고 다른 말은 하지 마세요.";

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(GeminiSttClient.class);

    private final GeminiClient client;
    private final String model;
    private final String fallbackModel;

    public GeminiSttClient(GeminiClient client, TalkDocProperties properties) {
        this.client = client;
        TalkDocProperties.Gemini.Models models = properties.ai().gemini().models();
        this.model = models.stt();
        String fb = models.sttFallback();
        this.fallbackModel = fb == null || fb.isBlank() || fb.strip().equals(model) ? null : fb.strip();
    }

    @Override
    public String transcribe(byte[] audio, String mimeType) {
        if (audio == null || audio.length == 0) {
            return "";
        }
        String mime = normalizeMime(mimeType);
        Map<String, Object> body = GeminiClient.request(
                List.of(GeminiClient.inlineDataPart(audio, mime), GeminiClient.textPart(PROMPT)),
                Map.of("temperature", 0));
        GeminiResponse response;
        try {
            response = client.generateContent(model, body, ErrorCode.STT_FAILED);
        } catch (ApiException e) {
            if (fallbackModel == null) throw e;
            log.warn("STT model {} failed ({}); falling back to {}", model, e.getMessage(), fallbackModel);
            response = client.generateContent(fallbackModel, body, ErrorCode.STT_FAILED);
        }
        String text = response.firstText();
        if (text == null) {
            throw new ApiException(ErrorCode.STT_FAILED, "Gemini returned no transcript");
        }
        return text.strip();
    }

    /** Gemini expects a bare media type; strip codec parameters like ";codecs=opus". */
    static String normalizeMime(String mimeType) {
        if (mimeType == null || mimeType.isBlank()) return "audio/webm";
        String base = mimeType.split(";")[0].strip().toLowerCase();
        return base.isEmpty() ? "audio/webm" : base;
    }
}

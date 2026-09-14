package com.talkdoc.backend.ai.gemini;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.talkdoc.backend.common.ApiException;
import com.talkdoc.backend.common.ErrorCode;
import com.talkdoc.backend.config.TalkDocProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.net.http.HttpClient;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Thin wrapper over the Gemini REST API (generateContent). Request bodies are built as maps with
 * Gemini's camelCase keys and responses are parsed with a private ObjectMapper, so the application's
 * global snake_case naming strategy never interferes.
 */
@Component
@ConditionalOnProperty(prefix = "talkdoc.ai", name = "provider", havingValue = "gemini")
public class GeminiClient {

    private static final Logger log = LoggerFactory.getLogger(GeminiClient.class);

    private final RestClient restClient;
    private final ObjectMapper mapper;
    private final String thinkingLevel;

    @Autowired // 생성자가 둘이라 Spring 이 고를 수 있게 명시 (테스트용 패키지 생성자와 구분)
    public GeminiClient(TalkDocProperties properties, RestClient.Builder builder) {
        this(properties, builder, true, true);
    }

    /**
     * @param requireKey     fail fast when the API key is blank
     * @param applyTimeouts  install a JDK HttpClient request factory with the configured timeouts;
     *                       tests pass false so a MockRestServiceServer bound to the builder stays in place
     */
    GeminiClient(TalkDocProperties properties, RestClient.Builder builder, boolean requireKey, boolean applyTimeouts) {
        TalkDocProperties.Gemini gemini = properties.ai().gemini();
        String apiKey = gemini == null ? null : gemini.apiKey();
        this.thinkingLevel = gemini == null || gemini.thinkingLevel() == null || gemini.thinkingLevel().isBlank()
                ? null : gemini.thinkingLevel().strip();
        if (requireKey && (apiKey == null || apiKey.isBlank())) {
            throw new ApiException(ErrorCode.LLM_FAILED,
                    "GEMINI_API_KEY is not set but talkdoc.ai.provider=gemini");
        }
        if (applyTimeouts) {
            // HTTP/1.1 고정: JDK HttpClient 의 HTTP/2 경로에서 대용량(inline 오디오) 요청이 타임아웃까지 멈추는 경우가 있음
            HttpClient httpClient = HttpClient.newBuilder()
                    .version(HttpClient.Version.HTTP_1_1)
                    .connectTimeout(properties.ai().timeout())
                    .build();
            JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
            factory.setReadTimeout(properties.ai().timeout());
            builder.requestFactory(factory);
        }
        this.restClient = builder
                .baseUrl(gemini == null ? "https://generativelanguage.googleapis.com/v1beta" : gemini.baseUrl())
                .defaultHeader("x-goog-api-key", apiKey == null ? "" : apiKey)
                .build();
        this.mapper = JsonMapper.builder()
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .build();
    }

    /**
     * POST /models/{model}:generateContent
     *
     * @param failureCode error code to raise on any transport/provider failure
     */
    public GeminiResponse generateContent(String model, Map<String, Object> body, ErrorCode failureCode) {
        body = withThinkingConfig(model, body);
        long started = System.nanoTime();
        try {
            String raw = restClient.post()
                    .uri("/models/{model}:generateContent", model)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(String.class);
            log.info("Gemini {} ok in {} ms (generationConfig={})", model,
                    (System.nanoTime() - started) / 1_000_000, body.get("generationConfig"));
            if (raw == null || raw.isBlank()) {
                throw new ApiException(failureCode, "Gemini returned an empty response");
            }
            return mapper.readValue(raw, GeminiResponse.class);
        } catch (ApiException e) {
            throw e;
        } catch (RestClientResponseException e) {
            log.warn("Gemini call failed: model={} status={}", model, e.getStatusCode().value());
            throw new ApiException(failureCode, "Gemini API error " + e.getStatusCode().value(), e);
        } catch (Exception e) {
            log.warn("Gemini call failed: model={} reason={}", model, e.getClass().getSimpleName());
            throw new ApiException(failureCode, "Gemini API call failed: " + e.getMessage(), e);
        }
    }

    /** generationConfig 에 thinkingConfig 가 없으면 설정된 thinkingLevel 을 넣는다 (tts 모델 제외). */
    Map<String, Object> withThinkingConfig(String model, Map<String, Object> body) {
        if (thinkingLevel == null || model == null || model.contains("tts")) {
            return body;
        }
        Map<String, Object> out = new LinkedHashMap<>(body);
        Map<String, Object> config = new LinkedHashMap<>();
        Object existing = out.get("generationConfig");
        if (existing instanceof Map<?, ?> map) {
            map.forEach((k, v) -> config.put(String.valueOf(k), v));
        }
        config.putIfAbsent("thinkingConfig", Map.of("thinkingLevel", thinkingLevel));
        out.put("generationConfig", config);
        return out;
    }

    // ---- request builders -------------------------------------------------------------------

    public static Map<String, Object> textPart(String text) {
        return Map.of("text", text);
    }

    public static Map<String, Object> inlineDataPart(byte[] data, String mimeType) {
        return Map.of("inlineData", Map.of(
                "mimeType", mimeType,
                "data", Base64.getEncoder().encodeToString(data)));
    }

    public static Map<String, Object> request(List<Map<String, Object>> parts, Map<String, Object> generationConfig) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("contents", List.of(Map.of("role", "user", "parts", parts)));
        if (generationConfig != null && !generationConfig.isEmpty()) {
            body.put("generationConfig", generationConfig);
        }
        return body;
    }
}

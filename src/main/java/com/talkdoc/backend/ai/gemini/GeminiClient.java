package com.talkdoc.backend.ai.gemini;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.talkdoc.backend.common.ApiException;
import com.talkdoc.backend.common.ErrorCode;
import com.talkdoc.backend.config.TalkDocProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
        if (requireKey && (apiKey == null || apiKey.isBlank())) {
            throw new ApiException(ErrorCode.LLM_FAILED,
                    "GEMINI_API_KEY is not set but talkdoc.ai.provider=gemini");
        }
        if (applyTimeouts) {
            HttpClient httpClient = HttpClient.newBuilder().connectTimeout(properties.ai().timeout()).build();
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
        try {
            String raw = restClient.post()
                    .uri("/models/{model}:generateContent", model)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(String.class);
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

package com.talkdoc.backend.ai.gemini;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.talkdoc.backend.ai.AnswerGuard;
import com.talkdoc.backend.ai.LlmClient;
import com.talkdoc.backend.ai.model.IntentAnalysis;
import com.talkdoc.backend.answer.Conversation;
import com.talkdoc.backend.common.ApiException;
import com.talkdoc.backend.common.ErrorCode;
import com.talkdoc.backend.config.TalkDocProperties;
import com.talkdoc.backend.question.Intent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Gemini-backed LlmClient. Prompts live in resources/prompts/*.txt; every answer/summary passes through
 * {@link AnswerGuard} so the LLM can never add medical interpretation.
 */
@Component
@ConditionalOnProperty(prefix = "talkdoc.ai", name = "provider", havingValue = "gemini")
public class GeminiLlmClient implements LlmClient {

    private static final Logger log = LoggerFactory.getLogger(GeminiLlmClient.class);

    private static final Map<String, Object> INTENT_SCHEMA = Map.of(
            "type", "OBJECT",
            "properties", Map.of("intents", Map.of(
                    "type", "ARRAY",
                    "items", Map.of("type", "STRING",
                            "enum", List.of("BODY_LOCATION", "SYMPTOM", "HISTORY_STATE", "OTHER")))),
            "required", List.of("intents"));

    private final GeminiClient client;
    private final String model;
    private final ObjectMapper mapper = new ObjectMapper();
    private final String intentTemplate;
    private final String composeTemplate;
    private final String summarizeTemplate;

    public GeminiLlmClient(GeminiClient client, TalkDocProperties properties) {
        this.client = client;
        this.model = properties.ai().gemini().models().llm();
        this.intentTemplate = loadPrompt("intent.txt");
        this.composeTemplate = loadPrompt("compose-answer.txt");
        this.summarizeTemplate = loadPrompt("summarize.txt");
    }

    @Override
    public IntentAnalysis analyzeIntent(String questionText) {
        String prompt = intentTemplate.replace("{{question}}", nullSafe(questionText));
        Map<String, Object> body = GeminiClient.request(
                List.of(GeminiClient.textPart(prompt)),
                Map.of("temperature", 0,
                        "responseMimeType", "application/json",
                        "responseSchema", INTENT_SCHEMA));
        GeminiResponse response = client.generateContent(model, body, ErrorCode.LLM_FAILED);
        return parseIntents(response.firstText());
    }

    IntentAnalysis parseIntents(String json) {
        List<Intent> intents = new ArrayList<>();
        try {
            JsonNode node = mapper.readTree(json == null ? "" : json.strip());
            JsonNode arr = node.path("intents");
            if (arr.isArray()) {
                for (JsonNode item : arr) {
                    try {
                        Intent intent = Intent.valueOf(item.asText().strip().toUpperCase());
                        if (!intents.contains(intent)) intents.add(intent);
                    } catch (IllegalArgumentException ignored) {
                        log.debug("Unknown intent from LLM ignored: {}", item.asText());
                    }
                }
            }
        } catch (IOException e) {
            log.warn("Intent JSON unparsable; treating as OTHER");
        }
        boolean hasSupported = intents.stream().anyMatch(Intent::isSupported);
        if (hasSupported) intents.remove(Intent.OTHER);
        if (intents.isEmpty()) intents.add(Intent.OTHER);
        return new IntentAnalysis(List.copyOf(intents));
    }

    @Override
    public String composeAnswer(String questionText, List<String> labels, List<Conversation> priorContext) {
        String prompt = composeTemplate
                .replace("{{question}}", nullSafe(questionText))
                .replace("{{labels}}", String.join(", ", labels))
                .replace("{{context}}", renderContext(priorContext));
        Map<String, Object> body = GeminiClient.request(
                List.of(GeminiClient.textPart(prompt)),
                Map.of("temperature", 0.2, "maxOutputTokens", 80));
        GeminiResponse response = client.generateContent(model, body, ErrorCode.LLM_FAILED);
        return AnswerGuard.sanitize(response.firstText(), questionText, labels);
    }

    @Override
    public String summarize(List<Conversation> conversations) {
        if (conversations == null || conversations.isEmpty()) return "";
        String prompt = summarizeTemplate.replace("{{conversations}}", renderContext(conversations));
        Map<String, Object> body = GeminiClient.request(
                List.of(GeminiClient.textPart(prompt)),
                Map.of("temperature", 0.2, "maxOutputTokens", 120));
        GeminiResponse response = client.generateContent(model, body, ErrorCode.LLM_FAILED);
        return AnswerGuard.sanitizeSummary(response.firstText(), conversations);
    }

    static String renderContext(List<Conversation> conversations) {
        if (conversations == null || conversations.isEmpty()) return "(없음)";
        StringBuilder sb = new StringBuilder();
        for (Conversation c : conversations) {
            sb.append("- 질문: ").append(nullSafe(c.question()))
                    .append(" / 수어: ").append(c.signs() == null ? "" : String.join(", ", c.signs()))
                    .append(" / 답변: ").append(nullSafe(c.answer())).append('\n');
        }
        return sb.toString().strip();
    }

    private static String nullSafe(String s) {
        return s == null ? "" : s;
    }

    static String loadPrompt(String name) {
        try {
            return new ClassPathResource("prompts/" + name).getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new ApiException(ErrorCode.INTERNAL_ERROR, "Missing prompt template: " + name, e);
        }
    }
}

package com.talkdoc.backend.ai.gemini;

import com.talkdoc.backend.ai.model.IntentAnalysis;
import com.talkdoc.backend.answer.Conversation;
import com.talkdoc.backend.common.ApiException;
import com.talkdoc.backend.common.ErrorCode;
import com.talkdoc.backend.config.TalkDocProperties;
import com.talkdoc.backend.question.Intent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class GeminiLlmClientTest {

    private MockRestServiceServer server;
    private GeminiLlmClient llm;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        TalkDocProperties props = new TalkDocProperties(
                new TalkDocProperties.Session(Duration.ofHours(2)),
                new TalkDocProperties.Sign(0.75),
                new TalkDocProperties.Ai("gemini", Duration.ofSeconds(5),
                        new TalkDocProperties.Gemini("test-key", "https://gemini.test/v1beta",
                                new TalkDocProperties.Gemini.Models("stt-m", "llm-m", "tts-m"))),
                new TalkDocProperties.SignAi("mock", "http://localhost:8000", Duration.ofSeconds(5)),
                new TalkDocProperties.Websocket("*"));
        GeminiClient client = new GeminiClient(props, builder, true, false);
        llm = new GeminiLlmClient(client, props);
    }

    @Test
    void analyzeIntentParsesStructuredJson() {
        server.expect(requestTo("https://gemini.test/v1beta/models/llm-m:generateContent"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("x-goog-api-key", "test-key"))
                .andExpect(jsonPath("$.generationConfig.responseMimeType").value("application/json"))
                .andExpect(jsonPath("$.contents[0].parts[0].text").exists())
                .andRespond(withSuccess(candidate("{\"intents\":[\"BODY_LOCATION\",\"SYMPTOM\",\"OTHER\"]}"),
                        MediaType.APPLICATION_JSON));

        IntentAnalysis analysis = llm.analyzeIntent("어디가 아파서 오셨어요?");
        assertThat(analysis.intents()).containsExactly(Intent.BODY_LOCATION, Intent.SYMPTOM);
        server.verify();
    }

    @Test
    void composeAnswerIsGuarded() {
        server.expect(requestTo("https://gemini.test/v1beta/models/llm-m:generateContent"))
                .andRespond(withSuccess(candidate("배가 아프고 장염이 의심됩니다."), MediaType.APPLICATION_JSON));
        assertThat(llm.composeAnswer("어디가 아파요?", List.of("배", "아프다"), List.of())).isEqualTo("배가 아파요.");
    }

    @Test
    void summarizeRendersContextAndKeepsCleanText() {
        server.expect(requestTo("https://gemini.test/v1beta/models/llm-m:generateContent"))
                .andExpect(jsonPath("$.contents[0].parts[0].text").value(org.hamcrest.Matchers.containsString("배가 아파요.")))
                .andRespond(withSuccess(candidate("환자는 복부 통증을 호소함."), MediaType.APPLICATION_JSON));
        List<Conversation> convs = List.of(new Conversation("a1", "q1", "어디가 아파요?",
                List.of(Intent.BODY_LOCATION), List.of("배", "아프다"), "배가 아파요.", Instant.now()));
        assertThat(llm.summarize(convs)).isEqualTo("환자는 복부 통증을 호소함.");
    }

    @Test
    void providerErrorBecomesLlmFailed() {
        server.expect(requestTo("https://gemini.test/v1beta/models/llm-m:generateContent"))
                .andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS));
        assertThatThrownBy(() -> llm.analyzeIntent("q"))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).code()).isEqualTo(ErrorCode.LLM_FAILED);
    }

    @Test
    void parseIntentsFallsBackToOther() {
        assertThat(llm.parseIntents("not json").intents()).containsExactly(Intent.OTHER);
        assertThat(llm.parseIntents("{\"intents\":[]}").intents()).containsExactly(Intent.OTHER);
        assertThat(llm.parseIntents("{\"intents\":[\"FOO\",\"history_state\"]}").intents())
                .containsExactly(Intent.HISTORY_STATE);
    }

    private static String candidate(String text) {
        String escaped = text.replace("\\", "\\\\").replace("\"", "\\\"");
        return "{\"candidates\":[{\"content\":{\"role\":\"model\",\"parts\":[{\"text\":\"" + escaped + "\"}]},"
                + "\"finishReason\":\"STOP\"}],\"usageMetadata\":{\"totalTokenCount\":10}}";
    }
}

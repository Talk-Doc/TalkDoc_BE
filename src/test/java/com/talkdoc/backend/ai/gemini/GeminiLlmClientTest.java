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
    private GeminiSttClient stt;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        TalkDocProperties props = new TalkDocProperties(
                new TalkDocProperties.Session(Duration.ofHours(2)),
                new TalkDocProperties.Sign(0.75),
                new TalkDocProperties.Ai("gemini", Duration.ofSeconds(5),
                        new TalkDocProperties.Gemini("test-key", "https://gemini.test/v1beta",
                                new TalkDocProperties.Gemini.Models("stt-m", "stt-fb", "llm-m", "tts-m"), null)),
                new TalkDocProperties.SignAi("mock", "http://localhost:5001", Duration.ofSeconds(5), Duration.ofSeconds(60), null, 2),
                new TalkDocProperties.Websocket("*"));
        GeminiClient client = new GeminiClient(props, builder, true, false);
        llm = new GeminiLlmClient(client, props);
        stt = new GeminiSttClient(client, props);
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
        List<Conversation> convs = List.of(Conversation.confirmed("a1", "q1", "어디가 아파요?",
                List.of(Intent.BODY_LOCATION), List.of("배", "아프다"), "배가 아파요.", Instant.now(), 1));
        assertThat(llm.summarize(convs)).isEqualTo("환자는 복부 통증을 호소함.");
    }

    @Test
    void providerErrorBecomesLlmFailed() {
        // 429 is transient, so the client retries MAX_RETRIES times before giving up.
        for (int i = 0; i <= GeminiClient.MAX_RETRIES; i++) {
            server.expect(requestTo("https://gemini.test/v1beta/models/llm-m:generateContent"))
                    .andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS));
        }
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
        assertThat(llm.parseIntents("{\"intents\":[\"DURATION\"]}").intents())
                .containsExactly(Intent.DURATION);
    }

    @Test
    void transientErrorIsRetriedThenSucceeds() {
        server.expect(requestTo("https://gemini.test/v1beta/models/llm-m:generateContent"))
                .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE).body("{\"error\":{\"code\":503}}"));
        server.expect(requestTo("https://gemini.test/v1beta/models/llm-m:generateContent"))
                .andRespond(withSuccess(candidate("{\"intents\":[\"SYMPTOM\"]}"), MediaType.APPLICATION_JSON));

        IntentAnalysis analysis = llm.analyzeIntent("어떤 증상이 있으세요?");

        assertThat(analysis.intents()).containsExactly(Intent.SYMPTOM);
        server.verify();
    }

    @Test
    void transientErrorGivesUpAfterMaxRetries() {
        for (int i = 0; i <= GeminiClient.MAX_RETRIES; i++) {
            server.expect(requestTo("https://gemini.test/v1beta/models/llm-m:generateContent"))
                    .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));
        }

        assertThatThrownBy(() -> llm.analyzeIntent("어떤 증상이 있으세요?"))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("503");
        server.verify();
    }

    @Test
    void nonTransientErrorIsNotRetried() {
        server.expect(requestTo("https://gemini.test/v1beta/models/llm-m:generateContent"))
                .andRespond(withStatus(HttpStatus.BAD_REQUEST));

        assertThatThrownBy(() -> llm.analyzeIntent("어떤 증상이 있으세요?"))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("400");
        server.verify();
    }

    @Test
    void sttFallsBackToSecondModelWhenPrimaryKeepsFailing() {
        for (int i = 0; i <= GeminiClient.MAX_RETRIES; i++) {
            server.expect(requestTo("https://gemini.test/v1beta/models/stt-m:generateContent"))
                    .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));
        }
        server.expect(requestTo("https://gemini.test/v1beta/models/stt-fb:generateContent"))
                .andRespond(withSuccess(candidate("어디가 아파서 오셨어요?"), MediaType.APPLICATION_JSON));

        String text = stt.transcribe(new byte[]{1, 2, 3}, "audio/webm;codecs=opus");

        assertThat(text).isEqualTo("어디가 아파서 오셨어요?");
        server.verify();
    }

    private static String candidate(String text) {
        String escaped = text.replace("\\", "\\\\").replace("\"", "\\\"");
        return "{\"candidates\":[{\"content\":{\"role\":\"model\",\"parts\":[{\"text\":\"" + escaped + "\"}]},"
                + "\"finishReason\":\"STOP\"}],\"usageMetadata\":{\"totalTokenCount\":10}}";
    }
}

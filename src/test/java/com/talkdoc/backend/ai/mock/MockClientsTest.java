package com.talkdoc.backend.ai.mock;

import com.talkdoc.backend.ai.model.IntentAnalysis;
import com.talkdoc.backend.ai.model.SignPrediction;
import com.talkdoc.backend.ai.model.TtsAudio;
import com.talkdoc.backend.question.Intent;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class MockClientsTest {

    @Test
    void sttReturnsDefaultOrEmbeddedText() {
        MockSttClient stt = new MockSttClient();
        assertThat(stt.transcribe(new byte[]{1, 2, 3, 0}, "audio/webm")).isEqualTo(MockSttClient.DEFAULT_TRANSCRIPT);
        assertThat(stt.transcribe("드시는 약 있으세요?".getBytes(StandardCharsets.UTF_8), "audio/webm"))
                .isEqualTo("드시는 약 있으세요?");
        assertThat(stt.transcribe(new byte[0], "audio/webm")).isEmpty();
    }

    @Test
    void llmIntentRules() {
        MockLlmClient llm = new MockLlmClient();
        assertThat(llm.analyzeIntent("어디가 아파서 오셨어요?").intents())
                .containsExactly(Intent.BODY_LOCATION, Intent.SYMPTOM);
        assertThat(llm.analyzeIntent("드시는 약 있으세요?").intents()).containsExactly(Intent.HISTORY_STATE);
        IntentAnalysis other = llm.analyzeIntent("어제 뭐 드셨어요?");
        assertThat(other.intents()).containsExactly(Intent.OTHER);
        assertThat(other.candidates()).isEmpty();
        assertThat(llm.composeAnswer("q", List.of("배", "아프다"), List.of())).isEqualTo("배가 아파요.");
    }

    @Test
    void signMockReturnsOneWordAndSupportsOverrides() {
        MockSignRecognitionClient sign = new MockSignRecognitionClient();

        SignPrediction demo = sign.predict(new byte[10], "video/webm", null);
        assertThat(demo.label()).isEqualTo("배");
        assertThat(demo.confidence()).isEqualTo(0.94);
        assertThat(demo.accepted()).isNull();
        assertThat(demo.reason()).isEqualTo("THRESHOLD_NOT_CONFIGURED");
        assertThat(demo.modelVersion()).isEqualTo("mock");
        assertThat(demo.requestId()).isNotBlank();

        // 영상 1개당 단어 1개: 라벨을 여러 개 줘도 첫 번째만 쓴다
        SignPrediction override = sign.predict(new byte[10], "video/webm;labels=머리,어지럽다", 3.0);
        assertThat(override.label()).isEqualTo("머리");
        assertThat(override.confidence()).isEqualTo(0.9);

        assertThat(sign.predict(new byte[10], "video/webm;labels=\"머리,기침\"", null).label()).isEqualTo("머리");

        SignPrediction lowConfidence = sign.predict(new byte[10], "video/webm;labels=머리;confidence=0.5", null);
        assertThat(lowConfidence.label()).isEqualTo("머리");
        assertThat(lowConfidence.confidence()).isEqualTo(0.5);

        SignPrediction noHands = sign.predict(new byte[10], "video/webm;reason=INSUFFICIENT_LANDMARKS", null);
        assertThat(noHands.label()).isNull();
        assertThat(noHands.confidence()).isNull();
        assertThat(noHands.accepted()).isFalse();
        assertThat(noHands.reason()).isEqualTo("INSUFFICIENT_LANDMARKS");
    }

    @Test
    void ttsReturnsWav() {
        TtsAudio audio = new MockTtsClient().synthesize("배가 아파요.");
        assertThat(audio.mimeType()).isEqualTo("audio/wav");
        assertThat(new String(audio.data(), 0, 4, StandardCharsets.US_ASCII)).isEqualTo("RIFF");
        assertThat(audio.data().length).isGreaterThan(44);
    }
}

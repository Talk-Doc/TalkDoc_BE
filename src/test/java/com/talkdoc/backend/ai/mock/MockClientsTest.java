package com.talkdoc.backend.ai.mock;

import com.talkdoc.backend.ai.model.IntentAnalysis;
import com.talkdoc.backend.ai.model.SignResult;
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
    void signMockPrefersDemoPairAndSupportsOverride() {
        MockSignRecognitionClient sign = new MockSignRecognitionClient();
        List<String> candidates = Intent.candidatesFor(List.of(Intent.BODY_LOCATION, Intent.SYMPTOM));
        List<SignResult> demo = sign.recognize(new byte[10], "video/webm", List.of(), candidates);
        assertThat(demo).extracting(SignResult::label).containsExactly("배", "아프다");
        assertThat(sign.recognize(new byte[10], "video/webm;labels=머리,어지럽다", List.of(), candidates))
                .extracting(SignResult::label).containsExactly("머리", "어지럽다");
        assertThat(sign.recognize(new byte[10], "video/webm", List.of(), List.of())).isEmpty();
        assertThat(sign.recognize(new byte[10], "video/webm", List.of(), List.of("약")))
                .extracting(SignResult::label).containsExactly("약");
    }

    @Test
    void ttsReturnsWav() {
        TtsAudio audio = new MockTtsClient().synthesize("배가 아파요.");
        assertThat(audio.mimeType()).isEqualTo("audio/wav");
        assertThat(new String(audio.data(), 0, 4, StandardCharsets.US_ASCII)).isEqualTo("RIFF");
        assertThat(audio.data().length).isGreaterThan(44);
    }
}

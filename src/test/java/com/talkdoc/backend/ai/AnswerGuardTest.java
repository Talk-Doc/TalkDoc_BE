package com.talkdoc.backend.ai;

import com.talkdoc.backend.answer.Conversation;
import com.talkdoc.backend.question.Intent;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AnswerGuardTest {

    @Test
    void fallbackBuildsExpectedSentences() {
        assertThat(AnswerGuard.fallback("어디가 아파서 오셨어요?", List.of("배", "아프다"))).isEqualTo("배가 아파요.");
        assertThat(AnswerGuard.fallback("", List.of("머리", "어지럽다"))).isEqualTo("머리가 어지러워요.");
        assertThat(AnswerGuard.fallback("", List.of("배", "아프다", "설사"))).isEqualTo("배가 아프고 설사를 해요.");
        assertThat(AnswerGuard.fallback("", List.of("기침"))).isEqualTo("기침을 해요.");
        assertThat(AnswerGuard.fallback("", List.of("약"))).isEqualTo("약을 먹고 있어요.");
        assertThat(AnswerGuard.fallback("", List.of("알레르기"))).isEqualTo("알레르기가 있어요.");
        assertThat(AnswerGuard.fallback("", List.of("당뇨병"))).isEqualTo("당뇨병이 있어요.");
        assertThat(AnswerGuard.fallback("", List.of("임신"))).isEqualTo("임신 중이에요.");
        assertThat(AnswerGuard.fallback("", List.of("감기"))).isEqualTo("감기에 걸렸어요.");
        assertThat(AnswerGuard.fallback("", List.of("배"))).isEqualTo("배요.");
        assertThat(AnswerGuard.fallback("", List.of("머리", "배", "아프다"))).isEqualTo("머리와 배가 아파요.");
        assertThat(AnswerGuard.fallback("", List.of("팔", "붓다"))).isEqualTo("팔이 부어요.");
        assertThat(AnswerGuard.fallback("", List.of())).isEmpty();
    }

    @Test
    void containsAllLabelsHandlesConjugation() {
        assertThat(AnswerGuard.containsAllLabels("배가 아파요.", List.of("배", "아프다"))).isTrue();
        assertThat(AnswerGuard.containsAllLabels("머리가 어지러워요.", List.of("머리", "어지럽다"))).isTrue();
        assertThat(AnswerGuard.containsAllLabels("팔이 부어요.", List.of("팔", "붓다"))).isTrue();
        assertThat(AnswerGuard.containsAllLabels("숨이 차요.", List.of("숨차다"))).isTrue();
        assertThat(AnswerGuard.containsAllLabels("배가 아파요.", List.of("배", "설사"))).isFalse();
    }

    @Test
    void sanitizeKeepsCleanOutputAndRejectsInterpretation() {
        List<String> labels = List.of("배", "아프다");
        assertThat(AnswerGuard.sanitize("배가 아파요", "q", labels)).isEqualTo("배가 아파요.");
        assertThat(AnswerGuard.sanitize("\"배가 아파요.\"\n설명", "q", labels)).isEqualTo("배가 아파요.");
        assertThat(AnswerGuard.sanitize("배가 아프고 장염이 의심됩니다.", "q", labels)).isEqualTo("배가 아파요.");
        assertThat(AnswerGuard.sanitize("머리가 아파요.", "q", labels)).isEqualTo("배가 아파요.");
        assertThat(AnswerGuard.sanitize("", "q", labels)).isEqualTo("배가 아파요.");
    }

    @Test
    void summaryFallbackAndSanitize() {
        List<Conversation> convs = List.of(
                conv("배가 아파요."), conv("기침을 해요."));
        assertThat(AnswerGuard.summaryFallback(convs)).isEqualTo("환자는 배가 아파요, 기침을 해요 라고 답함.");
        assertThat(AnswerGuard.sanitizeSummary("환자는 복부 통증과 기침을 호소함", convs))
                .isEqualTo("환자는 복부 통증과 기침을 호소함.");
        assertThat(AnswerGuard.sanitizeSummary("환자는 장염이 의심됨.", convs))
                .isEqualTo("환자는 배가 아파요, 기침을 해요 라고 답함.");
        assertThat(AnswerGuard.summaryFallback(List.of())).isEmpty();
    }

    @Test
    void batchimDetection() {
        assertThat(AnswerGuard.hasBatchim("배")).isFalse();
        assertThat(AnswerGuard.hasBatchim("팔")).isTrue();
        assertThat(AnswerGuard.hasBatchim("abc")).isFalse();
    }

    private static Conversation conv(String answer) {
        return new Conversation("a", "q", "질문", List.of(Intent.SYMPTOM), List.of(), answer, Instant.now());
    }
}

package com.talkdoc.backend.question;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static org.assertj.core.api.Assertions.assertThat;

class StaticAnswerModeResolverTest {

    private final StaticAnswerModeResolver resolver = new StaticAnswerModeResolver();

    @ParameterizedTest
    @EnumSource(value = Intent.class, names = {"BODY_LOCATION", "SYMPTOM", "HISTORY_STATE", "OTHER"})
    void signBasedIntents_requireSignsAndHaveNoCards(Intent intent) {
        assertThat(resolver.resolve(intent, "아무 질문")).isEqualTo(AnswerMode.SIGN_REQUIRED);
        assertThat(resolver.cardOptions(intent, "아무 질문")).isEmpty();
    }

    @Test
    void duration_isCardSelectWithFixedOptions() {
        assertThat(resolver.resolve(Intent.DURATION, "언제부터 아팠어요?")).isEqualTo(AnswerMode.CARD_SELECT);
        assertThat(resolver.cardOptions(Intent.DURATION, "언제부터 아팠어요?"))
                .containsExactly("오늘부터", "어제부터", "2~3일 전부터", "1주일 전부터", "2주 이상", "한 달 이상");
    }

    @Test
    void severityFrequencyYesNo_areCardSelectWithFixedOptions() {
        assertThat(resolver.resolve(Intent.SEVERITY, "얼마나 아파요?")).isEqualTo(AnswerMode.CARD_SELECT);
        assertThat(resolver.cardOptions(Intent.SEVERITY, "얼마나 아파요?"))
                .containsExactly("거의 없음", "약간", "보통", "심함", "참기 힘듦");
        assertThat(resolver.resolve(Intent.FREQUENCY, "얼마나 자주 그래요?")).isEqualTo(AnswerMode.CARD_SELECT);
        assertThat(resolver.cardOptions(Intent.FREQUENCY, "얼마나 자주 그래요?"))
                .containsExactly("계속", "하루 여러 번", "하루 한 번", "이틀에 한 번", "가끔");
        assertThat(resolver.resolve(Intent.YES_NO, "약 드세요?")).isEqualTo(AnswerMode.CARD_SELECT);
        assertThat(resolver.cardOptions(Intent.YES_NO, "약 드세요?"))
                .containsExactly("네", "아니요", "잘 모르겠어요");
    }

    @Test
    void choice_isCardSelectButOptionsComeFromTheLlm() {
        assertThat(resolver.resolve(Intent.CHOICE, "어느 쪽 다리가 아파요?")).isEqualTo(AnswerMode.CARD_SELECT);
        assertThat(resolver.cardOptions(Intent.CHOICE, "어느 쪽 다리가 아파요?")).isEmpty();
    }
}

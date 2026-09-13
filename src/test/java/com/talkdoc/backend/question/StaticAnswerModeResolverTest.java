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
                .containsExactly("오늘부터", "어제부터", "2~3일 전부터", "1주일 이상");
    }
}

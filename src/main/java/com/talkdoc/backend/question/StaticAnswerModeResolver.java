package com.talkdoc.backend.question;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * Fixed per-Intent lookup table, curated by the team rather than generated per-call by the LLM.
 * Rationale: card wording must stay consistent across calls for the same question type (so the
 * UI and tests can rely on it), which a freely-generating LLM can't guarantee. Extend the map
 * below when a new closed-form question type is identified; intents not listed default to
 * {@link AnswerMode#SIGN_REQUIRED} with no cards.
 */
@Component
public class StaticAnswerModeResolver implements AnswerModeResolver {

    private record Entry(AnswerMode mode, List<String> cardOptions) {
    }

    private static final Entry SIGN_REQUIRED = new Entry(AnswerMode.SIGN_REQUIRED, List.of());

    private static final Map<Intent, Entry> TABLE = Map.of(
            Intent.BODY_LOCATION, SIGN_REQUIRED,
            Intent.SYMPTOM, SIGN_REQUIRED,
            Intent.HISTORY_STATE, SIGN_REQUIRED,
            Intent.OTHER, SIGN_REQUIRED,
            Intent.DURATION, new Entry(AnswerMode.CARD_SELECT,
                    List.of("오늘부터", "어제부터", "2~3일 전부터", "1주일 이상")),
            Intent.SEVERITY, new Entry(AnswerMode.CARD_SELECT,
                    List.of("약간", "보통", "심함", "참기 힘듦")),
            Intent.FREQUENCY, new Entry(AnswerMode.CARD_SELECT,
                    List.of("계속", "하루 여러 번", "하루 한 번", "가끔")),
            Intent.YES_NO, new Entry(AnswerMode.CARD_SELECT,
                    List.of("네", "아니요", "잘 모르겠어요")));

    @Override
    public AnswerMode resolve(Intent intent, String questionText) {
        return TABLE.getOrDefault(intent, SIGN_REQUIRED).mode();
    }

    @Override
    public List<String> cardOptions(Intent intent, String questionText) {
        return TABLE.getOrDefault(intent, SIGN_REQUIRED).cardOptions();
    }
}

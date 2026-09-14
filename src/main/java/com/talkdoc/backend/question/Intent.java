package com.talkdoc.backend.question;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * Question intents recognised by the LLM. Each intent owns the sign vocabulary the Vision AI
 * should restrict itself to (context-aware recognition, FR-08 / AI-05).
 */
public enum Intent {
    // 수어 어휘는 TalkDoc-VisionAI 모델이 실제로 학습한 15개 단어와 일치시킨다
    // (가슴·허리·기침·구토·알레르기는 모델에 없어 제외, 2026-09-14 팀 결정).
    BODY_LOCATION(List.of("머리", "목", "배", "팔", "다리")),
    SYMPTOM(List.of("아프다", "어지럽다", "설사", "숨차다", "답답하다", "붓다")),
    HISTORY_STATE(List.of("약", "감기", "임신", "당뇨병")),
    /** Fixed-answer question (e.g. "언제부터 아팠어요?"); answered via cards, not signs. See {@link StaticAnswerModeResolver}. */
    DURATION(List.of()),
    /** "얼마나 아파요?" — 통증/증상 강도. 카드 선택. */
    SEVERITY(List.of()),
    /** "얼마나 자주 그래요?" — 증상 빈도. 카드 선택. */
    FREQUENCY(List.of()),
    /** 특정 항목 하나의 유무를 묻는 예/아니오 질문 ("약 드세요?", "임신 중이세요?"). 카드 선택. */
    YES_NO(List.of()),
    OTHER(List.of());

    private final List<String> vocabulary;

    Intent(List<String> vocabulary) {
        this.vocabulary = vocabulary;
    }

    public List<String> vocabulary() {
        return vocabulary;
    }

    public boolean isSupported() {
        return this != OTHER;
    }

    /** Ordered union of the vocabularies of the given intents, duplicates removed. */
    public static List<String> candidatesFor(Collection<Intent> intents) {
        LinkedHashSet<String> out = new LinkedHashSet<>();
        for (Intent intent : intents) {
            out.addAll(intent.vocabulary());
        }
        return List.copyOf(out);
    }

    /** All supported labels, used for validation of recognised signs. */
    public static List<String> allLabels() {
        return candidatesFor(List.of(BODY_LOCATION, SYMPTOM, HISTORY_STATE));
    }
}

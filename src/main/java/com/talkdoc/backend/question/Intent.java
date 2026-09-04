package com.talkdoc.backend.question;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * Question intents recognised by the LLM. Each intent owns the sign vocabulary the Vision AI
 * should restrict itself to (context-aware recognition, FR-08 / AI-05).
 */
public enum Intent {
    BODY_LOCATION(List.of("머리", "목", "가슴", "배", "허리", "팔", "다리")),
    SYMPTOM(List.of("아프다", "어지럽다", "기침", "구토", "설사", "숨차다", "답답하다", "붓다")),
    HISTORY_STATE(List.of("약", "알레르기", "감기", "임신", "당뇨병")),
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

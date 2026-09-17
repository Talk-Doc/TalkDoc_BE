package com.talkdoc.backend.ai.model;

import com.talkdoc.backend.question.Intent;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * LLM output for a doctor's question. Candidates are derived from the intents by the service
 * (Intent.candidatesFor), so adapters only need to fill {@code intents}.
 *
 * @param cardOptions LLM-generated answer cards, only meaningful when the primary intent is
 *                    {@link Intent#CHOICE}; empty for every other intent (the server's fixed table wins).
 */
public record IntentAnalysis(List<Intent> intents, List<String> cardOptions) {

    /** Card options are limited so the patient screen stays scannable. */
    public static final int MAX_CARD_OPTIONS = 6;
    public static final int MAX_CARD_LENGTH = 20;

    public IntentAnalysis {
        intents = intents == null ? List.of() : List.copyOf(intents);
        cardOptions = sanitize(cardOptions);
    }

    public IntentAnalysis(List<Intent> intents) {
        this(intents, List.of());
    }

    public static IntentAnalysis of(Intent... intents) {
        return new IntentAnalysis(List.of(intents));
    }

    public static IntentAnalysis choice(List<String> cardOptions) {
        return new IntentAnalysis(List.of(Intent.CHOICE), cardOptions);
    }

    public static IntentAnalysis unsupported() {
        return new IntentAnalysis(List.of(Intent.OTHER));
    }

    public List<String> candidates() {
        return Intent.candidatesFor(intents);
    }

    /** Trims, strips list numbering, drops blanks/duplicates/over-long items, caps the count. */
    static List<String> sanitize(List<String> raw) {
        if (raw == null) return List.of();
        LinkedHashSet<String> out = new LinkedHashSet<>();
        for (String item : raw) {
            if (item == null) continue;
            String s = item.strip().replaceFirst("^[\\d０-９]+[.)]\\s*", "").replaceFirst("^[-•·]\\s*", "").strip();
            if (s.isEmpty() || s.length() > MAX_CARD_LENGTH) continue;
            out.add(s);
            if (out.size() >= MAX_CARD_OPTIONS) break;
        }
        return List.copyOf(new ArrayList<>(out));
    }
}

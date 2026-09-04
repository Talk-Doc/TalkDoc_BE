package com.talkdoc.backend.ai.model;

import com.talkdoc.backend.question.Intent;

import java.util.List;

/**
 * LLM output for a doctor's question. Candidates are derived from the intents by the service
 * (Intent.candidatesFor), so adapters only need to fill {@code intents}.
 */
public record IntentAnalysis(List<Intent> intents) {

    public static IntentAnalysis of(Intent... intents) {
        return new IntentAnalysis(List.of(intents));
    }

    public static IntentAnalysis unsupported() {
        return new IntentAnalysis(List.of(Intent.OTHER));
    }

    public List<String> candidates() {
        return Intent.candidatesFor(intents);
    }
}

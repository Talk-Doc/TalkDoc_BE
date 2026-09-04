package com.talkdoc.backend.ai.mock;

import com.talkdoc.backend.ai.AnswerGuard;
import com.talkdoc.backend.ai.LlmClient;
import com.talkdoc.backend.ai.model.IntentAnalysis;
import com.talkdoc.backend.answer.Conversation;
import com.talkdoc.backend.question.Intent;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/** Keyword-rule LLM stand-in so the whole flow works without an API key. */
@Component
@ConditionalOnProperty(prefix = "talkdoc.ai", name = "provider", havingValue = "mock", matchIfMissing = true)
public class MockLlmClient implements LlmClient {

    private static final List<String> BODY_KEYWORDS = List.of("어디", "부위", "어느 곳", "어느 부분");
    private static final List<String> SYMPTOM_KEYWORDS = List.of("증상", "어떻", "어떤", "아프", "아파", "아픈", "불편");
    private static final List<String> HISTORY_KEYWORDS = List.of("약", "알레르기", "병력", "지병", "임신", "당뇨");

    @Override
    public IntentAnalysis analyzeIntent(String questionText) {
        String q = questionText == null ? "" : questionText;
        List<Intent> intents = new ArrayList<>();
        if (containsAny(q, BODY_KEYWORDS)) intents.add(Intent.BODY_LOCATION);
        if (containsAny(q, SYMPTOM_KEYWORDS)) intents.add(Intent.SYMPTOM);
        if (containsAny(q, HISTORY_KEYWORDS)) intents.add(Intent.HISTORY_STATE);
        if (intents.isEmpty()) intents.add(Intent.OTHER);
        return new IntentAnalysis(List.copyOf(intents));
    }

    @Override
    public String composeAnswer(String questionText, List<String> labels, List<Conversation> priorContext) {
        return AnswerGuard.fallback(questionText, labels);
    }

    @Override
    public String summarize(List<Conversation> conversations) {
        return AnswerGuard.summaryFallback(conversations);
    }

    private static boolean containsAny(String text, List<String> keywords) {
        return keywords.stream().anyMatch(text::contains);
    }
}

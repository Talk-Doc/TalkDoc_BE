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

    private static final List<String> DURATION_KEYWORDS = List.of("언제부터", "며칠", "얼마나 됐", "얼마나 되셨", "얼마나 오래");
    private static final List<String> SEVERITY_KEYWORDS = List.of("얼마나 아프", "얼마나 아파", "얼마나 심", "심한가요", "심하세요", "참기 힘들", "통증 정도");
    private static final List<String> FREQUENCY_KEYWORDS = List.of("얼마나 자주", "몇 번", "자주 그래", "계속 그런");
    /** Non-medical / identity questions → OTHER before anything else. */
    private static final List<String> OTHER_KEYWORDS = List.of("성함", "이름", "나이", "주소", "연락처", "보호자", "드셨", "결제", "접수");
    /** A single named item asked as yes/no ("약 드세요?", "알레르기 있어요?") → YES_NO card. */
    private static final List<String> YES_NO_ITEMS = List.of("약", "알레르기", "임신", "당뇨", "감기", "수술", "고혈압", "흡연", "음주");
    /** Closed-form questions outside the fixed table → CHOICE with mock-generated cards. */
    private static final List<String> SIDE_KEYWORDS = List.of("어느 쪽", "왼쪽", "오른쪽");
    private static final List<String> PAIN_KIND_KEYWORDS = List.of("어떤 느낌", "어떻게 아프", "찌르", "쑤시");
    /** Open-form history questions ("어떤 지병이 있으세요?") stay HISTORY_STATE. */
    private static final List<String> OPEN_FORM_KEYWORDS = List.of("어떤", "무슨", "다른", "병력", "지병", "앓고");
    private static final List<String> BODY_KEYWORDS = List.of("어디", "부위", "어느 곳", "어느 부분");
    private static final List<String> SYMPTOM_KEYWORDS = List.of("증상", "어떻", "어떤", "아프", "아파", "아픈", "불편");
    private static final List<String> HISTORY_KEYWORDS = List.of("약", "알레르기", "병력", "지병", "임신", "당뇨");

    @Override
    public IntentAnalysis analyzeIntent(String questionText) {
        String q = questionText == null ? "" : questionText;
        List<Intent> intents = new ArrayList<>();
        // DURATION checked first and alone: "언제부터 아팠어요?" also matches SYMPTOM_KEYWORDS ("아프"),
        // but only the first (primary) intent drives answer_mode, so duration wording must win outright
        // rather than just being added before the others.
        if (containsAny(q, OTHER_KEYWORDS)) {
            return new IntentAnalysis(List.of(Intent.OTHER));
        }
        if (containsAny(q, DURATION_KEYWORDS)) {
            return new IntentAnalysis(List.of(Intent.DURATION));
        }
        if (containsAny(q, SEVERITY_KEYWORDS)) {
            return new IntentAnalysis(List.of(Intent.SEVERITY));
        }
        if (containsAny(q, FREQUENCY_KEYWORDS)) {
            return new IntentAnalysis(List.of(Intent.FREQUENCY));
        }
        if (containsAny(q, YES_NO_ITEMS) && !containsAny(q, OPEN_FORM_KEYWORDS)) {
            return new IntentAnalysis(List.of(Intent.YES_NO));
        }
        if (containsAny(q, SIDE_KEYWORDS)) {
            return IntentAnalysis.choice(List.of("왼쪽", "오른쪽", "양쪽"));
        }
        if (containsAny(q, PAIN_KIND_KEYWORDS)) {
            return IntentAnalysis.choice(List.of("찌르는 듯", "쑤시는 듯", "쥐어짜는 듯", "욱신거림", "타는 듯"));
        }
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

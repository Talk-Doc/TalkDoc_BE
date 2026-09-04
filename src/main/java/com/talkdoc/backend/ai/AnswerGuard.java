package com.talkdoc.backend.ai;

import com.talkdoc.backend.answer.Conversation;
import com.talkdoc.backend.question.Intent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Safety net around LLM output. The patient's answer must contain ONLY what the patient signed:
 * no added symptoms, causes, severity, diagnoses, prescriptions or advice. If an LLM sentence
 * violates that, the deterministic {@link #fallback(String, List)} sentence is used instead.
 * Shared by the mock and Gemini adapters.
 */
public final class AnswerGuard {

    private static final Logger log = LoggerFactory.getLogger(AnswerGuard.class);

    /** Words that indicate medical interpretation rather than restatement. */
    static final List<String> FORBIDDEN = List.of(
            "진단", "의심", "처방", "복용하세요", "권장", "가능성", "질환", "염증", "장염", "위염",
            "심각", "응급", "치료", "검사가 필요", "드세요", "하시는 것이 좋", "추정");

    /** connective form (…고) and sentence-final polite form for predicate labels. */
    private record Predicate(String connective, String polite) {
    }

    private static final Map<String, Predicate> PREDICATES = new LinkedHashMap<>();
    private static final Map<String, List<String>> STEMS = new LinkedHashMap<>();

    static {
        PREDICATES.put("아프다", new Predicate("아프고", "아파요"));
        PREDICATES.put("어지럽다", new Predicate("어지럽고", "어지러워요"));
        PREDICATES.put("기침", new Predicate("기침을 하고", "기침을 해요"));
        PREDICATES.put("구토", new Predicate("토하고", "토해요"));
        PREDICATES.put("설사", new Predicate("설사를 하고", "설사를 해요"));
        PREDICATES.put("숨차다", new Predicate("숨이 차고", "숨이 차요"));
        PREDICATES.put("답답하다", new Predicate("답답하고", "답답해요"));
        PREDICATES.put("붓다", new Predicate("붓고", "부어요"));
        PREDICATES.put("약", new Predicate("약을 먹고 있고", "약을 먹고 있어요"));
        PREDICATES.put("알레르기", new Predicate("알레르기가 있고", "알레르기가 있어요"));
        PREDICATES.put("감기", new Predicate("감기에 걸렸고", "감기에 걸렸어요"));
        PREDICATES.put("임신", new Predicate("임신 중이고", "임신 중이에요"));
        PREDICATES.put("당뇨병", new Predicate("당뇨병이 있고", "당뇨병이 있어요"));

        STEMS.put("아프다", List.of("아프", "아파"));
        STEMS.put("어지럽다", List.of("어지럽", "어지러"));
        STEMS.put("붓다", List.of("붓", "부어", "부었"));
        STEMS.put("숨차다", List.of("숨차", "숨이 차"));
        STEMS.put("답답하다", List.of("답답"));
        STEMS.put("구토", List.of("구토", "토해", "토하", "토했"));
        STEMS.put("기침", List.of("기침"));
        STEMS.put("설사", List.of("설사"));
        STEMS.put("당뇨병", List.of("당뇨"));
    }

    private AnswerGuard() {
    }

    /** True when every label (or an accepted conjugated stem of it) appears in the sentence. */
    public static boolean containsAllLabels(String sentence, List<String> labels) {
        if (sentence == null) return false;
        String s = sentence.replace(" ", "");
        for (String label : labels) {
            List<String> forms = STEMS.getOrDefault(label, List.of(label));
            boolean found = forms.stream().anyMatch(f -> s.contains(f.replace(" ", "")));
            if (!found) return false;
        }
        return true;
    }

    /** True when the text contains any word that adds medical interpretation. */
    public static boolean containsForbidden(String text) {
        if (text == null) return false;
        return FORBIDDEN.stream().anyMatch(text::contains);
    }

    /**
     * Deterministic Korean sentence built only from the labels.
     * ["배","아프다"] → "배가 아파요."  ["배","아프다","설사"] → "배가 아프고 설사를 해요."
     * ["머리","어지럽다"] → "머리가 어지러워요."  ["기침"] → "기침을 해요."  ["배"] → "배요."
     */
    public static String fallback(String questionText, List<String> labels) {
        List<String> subjects = new ArrayList<>();
        List<Predicate> predicates = new ArrayList<>();
        for (String label : labels) {
            if (label == null || label.isBlank()) continue;
            if (Intent.BODY_LOCATION.vocabulary().contains(label)) {
                subjects.add(label);
            } else if (PREDICATES.containsKey(label)) {
                predicates.add(PREDICATES.get(label));
            } else {
                subjects.add(label); // unknown label: restate verbatim rather than drop it
            }
        }
        if (subjects.isEmpty() && predicates.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        if (!subjects.isEmpty()) {
            for (int i = 0; i < subjects.size(); i++) {
                String subject = subjects.get(i);
                sb.append(subject);
                if (i < subjects.size() - 1) {
                    sb.append(hasBatchim(subject) ? "과 " : "와 ");
                }
            }
            if (predicates.isEmpty()) {
                return sb.append("요.").toString();
            }
            sb.append(hasBatchim(subjects.get(subjects.size() - 1)) ? "이 " : "가 ");
        }
        for (int i = 0; i < predicates.size(); i++) {
            Predicate p = predicates.get(i);
            if (i < predicates.size() - 1) {
                sb.append(p.connective()).append(' ');
            } else {
                sb.append(p.polite());
            }
        }
        return sb.append('.').toString();
    }

    /**
     * Cleans an LLM answer and replaces it with {@link #fallback} when it adds information,
     * drops a label, or contains medical interpretation.
     */
    public static String sanitize(String llmOutput, String questionText, List<String> labels) {
        String cleaned = firstLine(llmOutput);
        if (cleaned.isEmpty() || containsForbidden(cleaned) || !containsAllLabels(cleaned, labels)) {
            log.warn("LLM answer rejected by guard; using fallback for labels {}", labels);
            return fallback(questionText, labels);
        }
        return ensurePeriod(cleaned);
    }

    /** Statement-only summary fallback: "환자는 배가 아파요, 기침을 해요 라고 답함." */
    public static String summaryFallback(List<Conversation> conversations) {
        if (conversations == null || conversations.isEmpty()) return "";
        List<String> answers = new ArrayList<>();
        for (Conversation c : conversations) {
            if (c.answer() == null || c.answer().isBlank()) continue;
            String a = c.answer().trim();
            if (a.endsWith(".")) a = a.substring(0, a.length() - 1);
            answers.add(a);
        }
        if (answers.isEmpty()) return "";
        return "환자는 " + String.join(", ", answers) + " 라고 답함.";
    }

    public static String sanitizeSummary(String llmOutput, List<Conversation> conversations) {
        String cleaned = firstLine(llmOutput);
        if (cleaned.isEmpty() || containsForbidden(cleaned)) {
            log.warn("LLM summary rejected by guard; using fallback ({} conversations)", conversations.size());
            return summaryFallback(conversations);
        }
        return ensurePeriod(cleaned);
    }

    static String firstLine(String text) {
        if (text == null) return "";
        for (String line : text.strip().split("\\R")) {
            String t = line.strip();
            if (!t.isEmpty()) {
                if (t.startsWith("\"") && t.endsWith("\"") && t.length() > 1) {
                    t = t.substring(1, t.length() - 1).strip();
                }
                return t;
            }
        }
        return "";
    }

    static String ensurePeriod(String text) {
        if (text.isEmpty()) return text;
        char last = text.charAt(text.length() - 1);
        return (last == '.' || last == '!' || last == '?') ? text : text + ".";
    }

    /** True when the last Hangul syllable has a final consonant (받침). */
    static boolean hasBatchim(String word) {
        if (word == null || word.isEmpty()) return false;
        char c = word.charAt(word.length() - 1);
        if (c < 0xAC00 || c > 0xD7A3) return false;
        return (c - 0xAC00) % 28 != 0;
    }
}

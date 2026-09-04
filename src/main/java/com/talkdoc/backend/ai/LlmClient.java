package com.talkdoc.backend.ai;

import com.talkdoc.backend.ai.model.IntentAnalysis;
import com.talkdoc.backend.answer.Conversation;

import java.util.List;

/**
 * Language-model tasks. Implementations: MockLlmClient (default), GeminiLlmClient.
 * Must throw ApiException(ErrorCode.LLM_FAILED) on provider errors.
 *
 * Hard rules for composeAnswer/summarize (see resources/prompts): only restate what the patient signed,
 * never add symptoms, causes, diagnoses, prescriptions or advice; one polite Korean sentence.
 */
public interface LlmClient {

    /** Classifies the doctor's question into one or more intents; OTHER when unsupported. */
    IntentAnalysis analyzeIntent(String questionText);

    /**
     * Joins accepted sign labels into a natural Korean sentence in the context of the question.
     *
     * @param questionText the doctor's question, e.g. "어디가 아파서 오셨어요?"
     * @param labels       accepted labels in signing order, e.g. ["배", "아프다"]
     * @param priorContext previously confirmed pairs in this session (may be empty), for pronoun/context resolution only
     * @return e.g. "배가 아파요."
     */
    String composeAnswer(String questionText, List<String> labels, List<Conversation> priorContext);

    /** One-sentence statement-only summary of the confirmed conversation, e.g. "환자는 복부 통증과 설사 증상을 호소함." */
    String summarize(List<Conversation> conversations);
}

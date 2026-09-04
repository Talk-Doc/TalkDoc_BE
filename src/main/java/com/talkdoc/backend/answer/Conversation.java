package com.talkdoc.backend.answer;

import com.talkdoc.backend.question.Intent;

import java.time.Instant;
import java.util.List;

/**
 * One confirmed question/answer pair. Stored as JSON elements of the session conversations list.
 *
 * @param signs the accepted sign labels in signing order, e.g. ["배", "아프다"]
 * @param answer the natural-language sentence shown to the doctor, e.g. "배가 아파요."
 */
public record Conversation(
        String answerId,
        String questionId,
        String question,
        List<Intent> intents,
        List<String> signs,
        String answer,
        Instant confirmedAt
) {

    public Conversation withAnswer(String newAnswer) {
        return new Conversation(answerId, questionId, question, intents, signs, newAnswer, confirmedAt);
    }
}

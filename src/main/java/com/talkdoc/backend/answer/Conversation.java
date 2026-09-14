package com.talkdoc.backend.answer;

import com.talkdoc.backend.auth.Role;
import com.talkdoc.backend.question.Intent;

import java.time.Instant;
import java.util.List;

/**
 * One confirmed question/answer pair. Stored as JSON elements of the session conversations list.
 *
 * @param signs           the accepted sign labels in signing order, e.g. ["배", "아프다"]
 * @param answer          the natural-language sentence shown to the doctor, e.g. "배가 아파요."
 * @param questionVersion the version of the question this answer was confirmed against (older JSON
 *                        without the field reads back as 1)
 * @param version         1 at confirmation, +1 on every applied patient edit (older JSON reads back as 1)
 * @param editedBy        the role of the last applied edit, or null when never edited
 * @param editedAt        time of the last applied edit, or null when never edited
 * @param pendingEdit     a doctor's proposed rewrite awaiting the patient's re-confirmation, or null
 */
public record Conversation(
        String answerId,
        String questionId,
        String question,
        List<Intent> intents,
        List<String> signs,
        String answer,
        Instant confirmedAt,
        int questionVersion,
        int version,
        Role editedBy,
        Instant editedAt,
        PendingEdit pendingEdit
) {

    public Conversation {
        if (questionVersion <= 0) {
            questionVersion = 1;
        }
        if (version <= 0) {
            version = 1;
        }
    }

    /** A freshly confirmed answer: version 1, never edited, no pending edit. */
    public static Conversation confirmed(String answerId,
                                          String questionId,
                                          String question,
                                          List<Intent> intents,
                                          List<String> signs,
                                          String answer,
                                          Instant confirmedAt,
                                          int questionVersion) {
        return new Conversation(answerId, questionId, question, intents, signs, answer, confirmedAt,
                questionVersion, 1, null, null, null);
    }

    /** Replaces only the answer text; version and edit metadata are untouched. */
    public Conversation withAnswer(String newAnswer) {
        return new Conversation(answerId, questionId, question, intents, signs, newAnswer, confirmedAt,
                questionVersion, version, editedBy, editedAt, pendingEdit);
    }

    /** Stores a doctor's proposal without touching the confirmed answer or the version. */
    public Conversation withPendingEdit(PendingEdit edit) {
        return new Conversation(answerId, questionId, question, intents, signs, answer, confirmedAt,
                questionVersion, version, editedBy, editedAt, edit);
    }

    /** Applies an edit: new answer, version + 1, edit metadata set and any pending proposal cleared. */
    public Conversation applyEdit(String newAnswer, Role role, Instant at) {
        return new Conversation(answerId, questionId, question, intents, signs, newAnswer, confirmedAt,
                questionVersion, version + 1, role, at, null);
    }
}

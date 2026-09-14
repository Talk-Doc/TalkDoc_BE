package com.talkdoc.backend.question;

import java.time.Instant;
import java.util.List;

/**
 * The doctor's most recent question, awaiting a patient answer. Stored as JSON in the session hash.
 *
 * <p>A question keeps its {@code questionId} when the doctor edits it (PATCH
 * {@code /api/sessions/{sessionId}/questions/{questionId}}); only {@code version} is incremented and
 * {@code updatedAt} set. Answer drafts made against an older version are invalidated, so clients must
 * always send the version they saw when previewing or confirming.</p>
 *
 * @param answerMode  SIGN_REQUIRED (patient signs) or CARD_SELECT (patient picks one of cardOptions)
 * @param cardOptions empty when answerMode is SIGN_REQUIRED
 * @param version   1 for a freshly posted question, +1 on every edit. Older JSON without the field
 *                  deserializes to 1.
 * @param updatedAt time of the last edit, or null when the question was never edited
 */
public record PendingQuestion(
        String questionId,
        String text,
        List<Intent> intents,
        List<String> candidates,
        AnswerMode answerMode,
        List<String> cardOptions,
        Instant askedAt,
        int version,
        Instant updatedAt
) {

    public PendingQuestion {
        if (version <= 0) {
            version = 1;
        }
    }

    public Intent primaryIntent() {
        return intents == null || intents.isEmpty() ? Intent.OTHER : intents.get(0);
    }

    public boolean isSupported() {
        return intents != null && intents.stream().anyMatch(Intent::isSupported);
    }
}

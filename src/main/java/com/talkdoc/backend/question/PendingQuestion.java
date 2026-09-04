package com.talkdoc.backend.question;

import java.time.Instant;
import java.util.List;

/** The doctor's most recent question, awaiting a patient answer. Stored as JSON in the session hash. */
public record PendingQuestion(
        String questionId,
        String text,
        List<Intent> intents,
        List<String> candidates,
        Instant askedAt
) {

    public Intent primaryIntent() {
        return intents == null || intents.isEmpty() ? Intent.OTHER : intents.get(0);
    }

    public boolean isSupported() {
        return intents != null && intents.stream().anyMatch(Intent::isSupported);
    }
}

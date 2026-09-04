package com.talkdoc.backend.session;

import com.talkdoc.backend.question.PendingQuestion;

import java.time.Instant;

/**
 * Session state as exposed to services. Tokens are intentionally not part of this record;
 * they are only returned once at creation via {@link SessionTokens}.
 *
 * @param currentQuestion the question the patient is expected to answer next, or null
 */
public record Session(
        String sessionId,
        SessionStatus status,
        Instant createdAt,
        PendingQuestion currentQuestion
) {

    public boolean isActive() {
        return status == SessionStatus.ACTIVE;
    }

    public Session withCurrentQuestion(PendingQuestion question) {
        return new Session(sessionId, status, createdAt, question);
    }
}

package com.talkdoc.backend.session.dto;

import com.talkdoc.backend.answer.Conversation;
import com.talkdoc.backend.question.PendingQuestion;
import com.talkdoc.backend.session.Session;
import com.talkdoc.backend.session.SessionStatus;

import java.time.Instant;
import java.util.List;

/**
 * Response body for {@code GET /api/sessions/{sessionId}}. Serialized snake_case (global Jackson naming
 * strategy): {@code session_id, status, created_at, current_question, conversations}.
 */
public record SessionDetailResponse(
        String sessionId,
        SessionStatus status,
        Instant createdAt,
        PendingQuestion currentQuestion,
        List<Conversation> conversations
) {

    public static SessionDetailResponse of(Session session, List<Conversation> conversations) {
        return new SessionDetailResponse(
                session.sessionId(),
                session.status(),
                session.createdAt(),
                session.currentQuestion(),
                conversations
        );
    }
}

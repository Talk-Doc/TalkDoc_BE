package com.talkdoc.backend.session.dto;

import com.talkdoc.backend.answer.AnswerDraft;
import com.talkdoc.backend.answer.Conversation;
import com.talkdoc.backend.question.PendingQuestion;
import com.talkdoc.backend.session.Session;
import com.talkdoc.backend.session.SessionStatus;
import com.talkdoc.backend.sign.Recognition;

import java.time.Instant;
import java.util.List;

/**
 * Response body for {@code GET /api/sessions/{sessionId}}. Serialized snake_case (global Jackson naming
 * strategy, nulls omitted): {@code session_id, status, created_at, current_question, question_version,
 * conversations, drafts, recognitions}.
 *
 * <p>{@code drafts} and {@code recognitions} belong to the patient's own work-in-progress and are only
 * filled in for the patient; the doctor never sees an answer the patient has not confirmed. Confirmed
 * conversations (including a doctor's {@code pending_edit}) go to both roles.</p>
 *
 * @param questionVersion version of {@code current_question}, or null when no question is pending
 * @param drafts          the patient's still-open drafts for the current question (patient only)
 * @param recognitions    the patient's recognitions for the current question version (patient only)
 */
public record SessionDetailResponse(
        String sessionId,
        SessionStatus status,
        Instant createdAt,
        PendingQuestion currentQuestion,
        Integer questionVersion,
        List<Conversation> conversations,
        List<AnswerDraft> drafts,
        List<Recognition> recognitions
) {

    public static SessionDetailResponse of(Session session,
                                            List<Conversation> conversations,
                                            List<AnswerDraft> drafts,
                                            List<Recognition> recognitions) {
        PendingQuestion question = session.currentQuestion();
        return new SessionDetailResponse(
                session.sessionId(),
                session.status(),
                session.createdAt(),
                question,
                question == null ? null : question.version(),
                conversations,
                drafts,
                recognitions
        );
    }
}

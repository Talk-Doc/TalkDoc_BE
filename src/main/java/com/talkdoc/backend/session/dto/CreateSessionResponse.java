package com.talkdoc.backend.session.dto;

import com.talkdoc.backend.session.Session;
import com.talkdoc.backend.session.SessionTokens;

import java.time.Instant;

/**
 * Response body for {@code POST /api/sessions}. Serialized snake_case (global Jackson naming strategy):
 * {@code session_id, doctor_token, patient_token, created_at, patient_join_path}.
 */
public record CreateSessionResponse(
        String sessionId,
        String doctorToken,
        String patientToken,
        Instant createdAt,
        String patientJoinPath
) {

    public static CreateSessionResponse of(Session session, SessionTokens tokens) {
        String joinPath = "/join?token=" + tokens.patientToken();
        return new CreateSessionResponse(
                session.sessionId(),
                tokens.doctorToken(),
                tokens.patientToken(),
                session.createdAt(),
                joinPath
        );
    }
}

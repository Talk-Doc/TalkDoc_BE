package com.talkdoc.backend.common;

/**
 * Single source of truth for Redis key layout. Every key carries the session TTL.
 *
 * <pre>
 * session:{sessionId}                 hash   created_at, status, doctor_token, patient_token, current_question(JSON)
 * token:{token}                       string "{sessionId}:{ROLE}"
 * session:{sessionId}:conversations   list   Conversation JSON (RPUSH, ordered)
 * session:{sessionId}:recognitions    list   Recognition JSON (RPUSH, ordered by recognition time)
 * session:{sessionId}:drafts          hash   answerId -> AnswerDraft JSON
 * </pre>
 */
public final class RedisKeys {

    public static final String SESSION_FIELD_CREATED_AT = "created_at";
    public static final String SESSION_FIELD_STATUS = "status";
    public static final String SESSION_FIELD_DOCTOR_TOKEN = "doctor_token";
    public static final String SESSION_FIELD_PATIENT_TOKEN = "patient_token";
    public static final String SESSION_FIELD_CURRENT_QUESTION = "current_question";

    private RedisKeys() {
    }

    public static String session(String sessionId) {
        return "session:" + sessionId;
    }

    public static String token(String token) {
        return "token:" + token;
    }

    public static String conversations(String sessionId) {
        return "session:" + sessionId + ":conversations";
    }

    /** Ordered list of every sign recognition made in the session (RPUSH). */
    public static String recognitions(String sessionId) {
        return "session:" + sessionId + ":recognitions";
    }

    /** Hash of answer drafts created by /answer/preview, keyed by answerId. */
    public static String drafts(String sessionId) {
        return "session:" + sessionId + ":drafts";
    }
}

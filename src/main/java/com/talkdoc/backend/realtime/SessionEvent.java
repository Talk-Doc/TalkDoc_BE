package com.talkdoc.backend.realtime;

import java.time.Instant;

/**
 * Wire format pushed over the WebSocket:
 * {"type":"QUESTION_POSTED","session_id":"...","payload":{...},"timestamp":"..."}
 */
public record SessionEvent(EventType type, String sessionId, Object payload, Instant timestamp) {

    public static SessionEvent of(EventType type, String sessionId, Object payload) {
        return new SessionEvent(type, sessionId, payload, Instant.now());
    }
}

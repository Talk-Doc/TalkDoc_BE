package com.talkdoc.backend.realtime;

/**
 * Broadcasts events to every WebSocket client attached to a session. Implemented in WP4.
 * Services (WP1/WP2) depend only on this interface. Implementations must never throw
 * because a missing/closed socket is normal (the other party may not be connected yet).
 */
public interface SessionEventPublisher {

    void publish(SessionEvent event);

    /** Sends SESSION_CLOSED and closes every connection for the session. */
    void closeSession(String sessionId);
}

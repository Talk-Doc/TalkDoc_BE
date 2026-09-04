package com.talkdoc.backend.realtime;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.io.IOException;
import java.util.Map;

/**
 * Broadcasts {@link SessionEvent}s to every WebSocket connection attached to a session,
 * using the application's globally-configured (SNAKE_CASE) {@link ObjectMapper}.
 * Never throws: a missing/closed socket, or a serialisation failure, is logged and swallowed
 * so a broadcast failure can never fail the HTTP request that triggered it.
 */
@Component
public class WebSocketSessionEventPublisher implements SessionEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(WebSocketSessionEventPublisher.class);

    private final SessionSocketRegistry registry;
    private final ObjectMapper objectMapper;

    public WebSocketSessionEventPublisher(SessionSocketRegistry registry, ObjectMapper objectMapper) {
        this.registry = registry;
        this.objectMapper = objectMapper;
    }

    @Override
    public void publish(SessionEvent event) {
        try {
            String json = objectMapper.writeValueAsString(event);
            TextMessage message = new TextMessage(json);
            for (WebSocketSession session : registry.connections(event.sessionId())) {
                sendQuietly(session, message);
            }
        } catch (JsonProcessingException e) {
            log.warn("Failed to serialise SessionEvent type={} sessionId={}", event.type(), event.sessionId(), e);
        } catch (RuntimeException e) {
            log.warn("Unexpected failure publishing SessionEvent type={} sessionId={}", event.type(), event.sessionId(), e);
        }
    }

    @Override
    public void closeSession(String sessionId) {
        try {
            publish(SessionEvent.of(EventType.SESSION_CLOSED, sessionId, Map.of("session_id", sessionId)));
        } catch (RuntimeException e) {
            log.warn("Failed to publish SESSION_CLOSED for sessionId={}", sessionId, e);
        }
        try {
            registry.closeAll(sessionId, CloseStatus.NORMAL);
        } catch (RuntimeException e) {
            log.warn("Failed to close WebSocket connections for sessionId={}", sessionId, e);
        }
    }

    private void sendQuietly(WebSocketSession session, TextMessage message) {
        try {
            if (session.isOpen()) {
                session.sendMessage(message);
            }
        } catch (IOException | IllegalStateException e) {
            log.warn("Failed to deliver event, removing dead WebSocket session", e);
            registry.remove(session);
        }
    }
}

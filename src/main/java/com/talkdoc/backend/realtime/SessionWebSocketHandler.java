package com.talkdoc.backend.realtime;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.talkdoc.backend.auth.AuthenticatedPrincipal;
import com.talkdoc.backend.auth.Role;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.IOException;

/**
 * Handles the lifecycle of a single WebSocket connection attached to an intake session.
 * All state mutations happen over HTTP; this handler only registers/deregisters connections,
 * announces peer presence (PEER_JOINED), and answers client-initiated PING with PONG.
 */
@Component
public class SessionWebSocketHandler extends TextWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(SessionWebSocketHandler.class);

    private static final String FIELD_TYPE = "type";
    private static final String CLIENT_PING = "PING";
    private static final TextMessage PONG_MESSAGE = new TextMessage("{\"type\":\"PONG\"}");

    private final SessionSocketRegistry registry;
    private final ObjectMapper objectMapper;

    public SessionWebSocketHandler(SessionSocketRegistry registry, ObjectMapper objectMapper) {
        this.registry = registry;
        this.objectMapper = objectMapper;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        AuthenticatedPrincipal principal = principalOf(session);
        if (principal == null) {
            log.warn("WebSocket connection established without a principal attribute; closing");
            closeQuietly(session, CloseStatus.SERVER_ERROR);
            return;
        }

        String sessionId = principal.sessionId();
        Role role = principal.role();
        Role otherRole = otherRole(role);

        boolean peerAlreadyPresent = registry.roles(sessionId).contains(otherRole);
        WebSocketSession decorated = registry.add(sessionId, role, session);
        log.info("WebSocket connected: sessionId={} role={}", sessionId, role);

        notifyOthers(sessionId, decorated, peerJoinedMessage(sessionId, role));
        if (peerAlreadyPresent) {
            sendQuietly(decorated, peerJoinedMessage(sessionId, otherRole));
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        AuthenticatedPrincipal principal = principalOf(session);
        registry.remove(session);
        if (principal != null) {
            log.info("WebSocket disconnected: sessionId={} role={}", principal.sessionId(), principal.role());
        }
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        JsonNode node;
        try {
            node = objectMapper.readTree(message.getPayload());
        } catch (IOException e) {
            return; // ignore anything that isn't valid JSON; all mutations go through HTTP.
        }
        if (node == null) {
            return;
        }
        JsonNode type = node.get(FIELD_TYPE);
        if (type != null && CLIENT_PING.equals(type.asText())) {
            sendQuietly(session, PONG_MESSAGE);
        }
        // any other message type is ignored.
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) {
        log.warn("WebSocket transport error, closing connection", exception);
        registry.remove(session);
        closeQuietly(session, CloseStatus.SERVER_ERROR);
    }

    private void notifyOthers(String sessionId, WebSocketSession joiner, TextMessage message) {
        for (WebSocketSession ws : registry.connections(sessionId)) {
            if (ws.getId().equals(joiner.getId())) {
                continue;
            }
            sendQuietly(ws, message);
        }
    }

    private TextMessage peerJoinedMessage(String sessionId, Role role) {
        SessionEvent event = SessionEvent.of(EventType.PEER_JOINED, sessionId, new PeerJoinedPayload(role));
        try {
            return new TextMessage(objectMapper.writeValueAsString(event));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialise PEER_JOINED event", e);
        }
    }

    private void sendQuietly(WebSocketSession session, TextMessage message) {
        try {
            if (session.isOpen()) {
                session.sendMessage(message);
            }
        } catch (IOException | IllegalStateException e) {
            log.warn("Failed to deliver WebSocket message, removing dead session", e);
            registry.remove(session);
        }
    }

    private void closeQuietly(WebSocketSession session, CloseStatus status) {
        try {
            if (session.isOpen()) {
                session.close(status);
            }
        } catch (IOException ignored) {
            // best-effort close.
        }
    }

    private static Role otherRole(Role role) {
        return role == Role.DOCTOR ? Role.PATIENT : Role.DOCTOR;
    }

    private static AuthenticatedPrincipal principalOf(WebSocketSession session) {
        Object attr = session.getAttributes().get(SessionHandshakeInterceptor.ATTR_PRINCIPAL);
        return attr instanceof AuthenticatedPrincipal principal ? principal : null;
    }

    private record PeerJoinedPayload(Role role) {
    }
}

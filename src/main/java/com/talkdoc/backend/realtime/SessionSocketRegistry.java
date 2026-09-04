package com.talkdoc.backend.realtime;

import com.talkdoc.backend.auth.Role;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.stream.Collectors;

/**
 * Thread-safe registry of the WebSocket connections attached to each intake session.
 * At most two roles per session, but connections are tracked as a set so a stray
 * duplicate connection for the same role never overwrites an existing one silently.
 */
@Component
public class SessionSocketRegistry {

    private static final int SEND_TIME_LIMIT_MS = 5_000;
    private static final int BUFFER_SIZE_BYTES = 512 * 1024;

    private record Connection(WebSocketSession session, Role role) {
    }

    private final Map<String, Set<Connection>> connectionsBySession = new ConcurrentHashMap<>();
    /** raw or decorated WebSocket session id -> owning talkdoc sessionId, so remove() accepts either. */
    private final Map<String, String> sessionIdByWebSocketId = new ConcurrentHashMap<>();

    /**
     * Registers a new connection, wrapping it in a {@link ConcurrentWebSocketSessionDecorator}
     * so concurrent broadcast sends are safe. Returns the decorated session; callers should use
     * the returned instance (not the raw one) for any further interaction.
     */
    public WebSocketSession add(String sessionId, Role role, WebSocketSession ws) {
        WebSocketSession decorated = new ConcurrentWebSocketSessionDecorator(ws, SEND_TIME_LIMIT_MS, BUFFER_SIZE_BYTES);
        connectionsBySession
                .computeIfAbsent(sessionId, id -> new CopyOnWriteArraySet<>())
                .add(new Connection(decorated, role));
        sessionIdByWebSocketId.put(ws.getId(), sessionId);
        return decorated;
    }

    /** Removes a connection; accepts either the raw session or the decorated one returned by {@link #add}. */
    public void remove(WebSocketSession ws) {
        String sessionId = sessionIdByWebSocketId.remove(ws.getId());
        if (sessionId == null) {
            return;
        }
        connectionsBySession.computeIfPresent(sessionId, (id, connections) -> {
            connections.removeIf(c -> c.session().getId().equals(ws.getId()));
            return connections.isEmpty() ? null : connections;
        });
    }

    /** Snapshot of every WebSocket session currently attached to {@code sessionId}. */
    public List<WebSocketSession> connections(String sessionId) {
        Set<Connection> connections = connectionsBySession.get(sessionId);
        if (connections == null) {
            return List.of();
        }
        return connections.stream().map(Connection::session).collect(Collectors.toUnmodifiableList());
    }

    /** Snapshot of the roles currently connected to {@code sessionId}. */
    public Set<Role> roles(String sessionId) {
        Set<Connection> connections = connectionsBySession.get(sessionId);
        if (connections == null) {
            return Set.of();
        }
        return connections.stream().map(Connection::role).collect(Collectors.toUnmodifiableSet());
    }

    /** Closes and forgets every connection attached to {@code sessionId}. */
    public void closeAll(String sessionId, CloseStatus status) {
        Set<Connection> connections = connectionsBySession.remove(sessionId);
        if (connections == null) {
            return;
        }
        for (Connection connection : connections) {
            sessionIdByWebSocketId.remove(connection.session().getId());
            try {
                if (connection.session().isOpen()) {
                    connection.session().close(status);
                }
            } catch (IOException ignored) {
                // best-effort close; nothing more we can do here.
            }
        }
    }
}

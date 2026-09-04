package com.talkdoc.backend.realtime;

import com.talkdoc.backend.auth.AuthenticatedPrincipal;
import com.talkdoc.backend.auth.TokenResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Authenticates a WebSocket handshake for {@code GET /ws/sessions/{sessionId}?token=<token>}.
 * The token may also be supplied via the {@code X-Session-Token} header (REST-style fallback).
 * On success, the resolved {@link AuthenticatedPrincipal} and sessionId are stashed in the
 * handshake attributes for {@link SessionWebSocketHandler} to pick up.
 */
@Component
public class SessionHandshakeInterceptor implements HandshakeInterceptor {

    private static final Logger log = LoggerFactory.getLogger(SessionHandshakeInterceptor.class);

    private static final String PATH_PREFIX = "/ws/sessions/";
    private static final String QUERY_PARAM_TOKEN = "token";
    private static final String HEADER_TOKEN = "X-Session-Token";

    public static final String ATTR_PRINCIPAL = "principal";
    public static final String ATTR_SESSION_ID = "sessionId";

    private final TokenResolver tokenResolver;

    public SessionHandshakeInterceptor(TokenResolver tokenResolver) {
        this.tokenResolver = tokenResolver;
    }

    @Override
    public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                    WebSocketHandler wsHandler, Map<String, Object> attributes) {
        String sessionId = extractSessionId(request.getURI().getPath());
        String token = extractToken(request);

        if (sessionId == null || token == null) {
            response.setStatusCode(HttpStatus.UNAUTHORIZED);
            return false;
        }

        Optional<AuthenticatedPrincipal> resolved = tokenResolver.resolve(token);
        if (resolved.isEmpty()) {
            response.setStatusCode(HttpStatus.UNAUTHORIZED);
            return false;
        }

        AuthenticatedPrincipal principal = resolved.get();
        if (!principal.sessionId().equals(sessionId)) {
            response.setStatusCode(HttpStatus.FORBIDDEN);
            return false;
        }

        attributes.put(ATTR_PRINCIPAL, principal);
        attributes.put(ATTR_SESSION_ID, sessionId);
        log.debug("WebSocket handshake authorised: sessionId={} role={}", sessionId, principal.role());
        return true;
    }

    @Override
    public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                WebSocketHandler wsHandler, Exception exception) {
        // no-op
    }

    /** sessionId = last path segment after "/ws/sessions/". */
    static String extractSessionId(String path) {
        if (path == null) {
            return null;
        }
        int idx = path.indexOf(PATH_PREFIX);
        if (idx < 0) {
            return null;
        }
        String rest = path.substring(idx + PATH_PREFIX.length());
        while (rest.endsWith("/")) {
            rest = rest.substring(0, rest.length() - 1);
        }
        if (rest.isEmpty()) {
            return null;
        }
        int lastSlash = rest.lastIndexOf('/');
        String sessionId = lastSlash < 0 ? rest : rest.substring(lastSlash + 1);
        return sessionId.isBlank() ? null : sessionId;
    }

    private static String extractToken(ServerHttpRequest request) {
        String fromQuery = queryParam(request.getURI().getRawQuery(), QUERY_PARAM_TOKEN);
        if (fromQuery != null && !fromQuery.isBlank()) {
            return fromQuery;
        }
        List<String> headerValues = request.getHeaders().get(HEADER_TOKEN);
        if (headerValues != null && !headerValues.isEmpty() && !headerValues.get(0).isBlank()) {
            return headerValues.get(0).trim();
        }
        return null;
    }

    private static String queryParam(String rawQuery, String name) {
        if (rawQuery == null || rawQuery.isBlank()) {
            return null;
        }
        for (String pair : rawQuery.split("&")) {
            if (pair.isBlank()) {
                continue;
            }
            int eq = pair.indexOf('=');
            String rawKey = eq < 0 ? pair : pair.substring(0, eq);
            String rawValue = eq < 0 ? "" : pair.substring(eq + 1);
            String key = decode(rawKey);
            if (key.equals(name)) {
                return decode(rawValue);
            }
        }
        return null;
    }

    private static String decode(String value) {
        try {
            return URLDecoder.decode(value, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            return value;
        }
    }
}

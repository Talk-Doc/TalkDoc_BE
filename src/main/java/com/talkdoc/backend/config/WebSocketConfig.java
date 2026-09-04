package com.talkdoc.backend.config;

import com.talkdoc.backend.realtime.SessionHandshakeInterceptor;
import com.talkdoc.backend.realtime.SessionWebSocketHandler;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

/**
 * Registers the intake-session WebSocket endpoint at "/ws/sessions/{sessionId}".
 * Auth happens in {@link SessionHandshakeInterceptor} before the handshake completes.
 */
@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {

    private final SessionWebSocketHandler sessionWebSocketHandler;
    private final SessionHandshakeInterceptor sessionHandshakeInterceptor;
    private final TalkDocProperties properties;

    public WebSocketConfig(SessionWebSocketHandler sessionWebSocketHandler,
                            SessionHandshakeInterceptor sessionHandshakeInterceptor,
                            TalkDocProperties properties) {
        this.sessionWebSocketHandler = sessionWebSocketHandler;
        this.sessionHandshakeInterceptor = sessionHandshakeInterceptor;
        this.properties = properties;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(sessionWebSocketHandler, "/ws/sessions/{sessionId}")
                .addInterceptors(sessionHandshakeInterceptor)
                .setAllowedOriginPatterns(properties.websocket().allowedOriginArray());
    }
}

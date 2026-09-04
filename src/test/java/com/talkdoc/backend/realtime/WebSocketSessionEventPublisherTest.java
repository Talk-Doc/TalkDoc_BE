package com.talkdoc.backend.realtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Uses a hand-built ObjectMapper mirroring application.yml's spring.jackson config
 * (SNAKE_CASE naming, ISO-8601 Instants) since these are plain unit tests with no Spring context.
 */
class WebSocketSessionEventPublisherTest {

    private SessionSocketRegistry registry;
    private WebSocketSessionEventPublisher publisher;

    @BeforeEach
    void setUp() {
        registry = mock(SessionSocketRegistry.class);
        ObjectMapper objectMapper = JsonMapper.builder()
                .propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
                .build();
        objectMapper.registerModule(new JavaTimeModule());
        objectMapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        publisher = new WebSocketSessionEventPublisher(registry, objectMapper);
    }

    @Test
    void publishSendsSnakeCaseSerialisedEventToEveryConnection() throws IOException {
        WebSocketSession a = mock(WebSocketSession.class);
        WebSocketSession b = mock(WebSocketSession.class);
        when(a.isOpen()).thenReturn(true);
        when(b.isOpen()).thenReturn(true);
        when(registry.connections("session-1")).thenReturn(List.of(a, b));

        SessionEvent event = SessionEvent.of(EventType.QUESTION_POSTED, "session-1", Map.of("text", "hello"));
        publisher.publish(event);

        ArgumentCaptor<TextMessage> captor = ArgumentCaptor.forClass(TextMessage.class);
        verify(a).sendMessage(captor.capture());
        verify(b).sendMessage(any(TextMessage.class));

        String json = captor.getValue().getPayload();
        assertThat(json).contains("\"type\":\"QUESTION_POSTED\"")
                .contains("\"session_id\":\"session-1\"")
                .contains("\"payload\":{\"text\":\"hello\"}")
                .contains("\"timestamp\"");
    }

    @Test
    void publishRemovesDeadSessionButKeepsDeliveringToOthers() throws IOException {
        WebSocketSession dead = mock(WebSocketSession.class);
        WebSocketSession alive = mock(WebSocketSession.class);
        when(dead.isOpen()).thenReturn(true);
        when(alive.isOpen()).thenReturn(true);
        doThrow(new IOException("broken pipe")).when(dead).sendMessage(any());
        when(registry.connections("session-2")).thenReturn(List.of(dead, alive));

        publisher.publish(SessionEvent.of(EventType.ANSWER_CONFIRMED, "session-2", Map.of()));

        verify(registry).remove(dead);
        verify(alive).sendMessage(any(TextMessage.class));
    }

    @Test
    void publishNeverThrowsEvenIfTheRegistryBlowsUp() {
        when(registry.connections(any())).thenThrow(new RuntimeException("boom"));

        assertThatCode(() -> publisher.publish(SessionEvent.of(EventType.SESSION_CLOSED, "session-3", Map.of())))
                .doesNotThrowAnyException();
    }

    @Test
    void closeSessionPublishesSessionClosedThenClosesAllConnections() throws IOException {
        WebSocketSession session = mock(WebSocketSession.class);
        when(session.isOpen()).thenReturn(true);
        when(registry.connections("session-4")).thenReturn(List.of(session));

        publisher.closeSession("session-4");

        ArgumentCaptor<TextMessage> captor = ArgumentCaptor.forClass(TextMessage.class);
        verify(session).sendMessage(captor.capture());
        assertThat(captor.getValue().getPayload())
                .contains("\"type\":\"SESSION_CLOSED\"")
                .contains("\"session_id\":\"session-4\"");
        verify(registry).closeAll("session-4", CloseStatus.NORMAL);
    }

    @Test
    void closeSessionNeverThrowsEvenIfClosingConnectionsFails() {
        when(registry.connections(any())).thenReturn(List.of());
        doThrow(new RuntimeException("boom")).when(registry).closeAll(any(), any());

        assertThatCode(() -> publisher.closeSession("session-5")).doesNotThrowAnyException();
    }
}

package com.talkdoc.backend.realtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.talkdoc.backend.auth.AuthenticatedPrincipal;
import com.talkdoc.backend.auth.Role;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class SessionWebSocketHandlerTest {

    private SessionSocketRegistry registry;
    private SessionWebSocketHandler handler;

    @BeforeEach
    void setUp() {
        registry = mock(SessionSocketRegistry.class);
        ObjectMapper objectMapper = JsonMapper.builder()
                .propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
                .build();
        objectMapper.registerModule(new JavaTimeModule());
        handler = new SessionWebSocketHandler(registry, objectMapper);
    }

    private static WebSocketSession sessionWithPrincipal(String id, AuthenticatedPrincipal principal) {
        WebSocketSession session = mock(WebSocketSession.class);
        when(session.getId()).thenReturn(id);
        when(session.isOpen()).thenReturn(true);
        Map<String, Object> attrs = new HashMap<>();
        attrs.put(SessionHandshakeInterceptor.ATTR_PRINCIPAL, principal);
        when(session.getAttributes()).thenReturn(attrs);
        return session;
    }

    @Test
    void firstConnectionRegistersButNotifiesNoOne() throws Exception {
        AuthenticatedPrincipal doctor = new AuthenticatedPrincipal("s1", Role.DOCTOR, "tok");
        WebSocketSession raw = sessionWithPrincipal("raw1", doctor);
        WebSocketSession decorated = mock(WebSocketSession.class);
        when(decorated.getId()).thenReturn("raw1");
        when(registry.roles("s1")).thenReturn(Set.of());
        when(registry.add("s1", Role.DOCTOR, raw)).thenReturn(decorated);
        when(registry.connections("s1")).thenReturn(List.of(decorated));

        handler.afterConnectionEstablished(raw);

        verify(registry).add("s1", Role.DOCTOR, raw);
        verify(decorated, never()).sendMessage(any());
    }

    @Test
    void secondConnectionNotifiesBothSidesOfEachOthersRole() throws Exception {
        AuthenticatedPrincipal patient = new AuthenticatedPrincipal("s1", Role.PATIENT, "tok2");
        WebSocketSession raw = sessionWithPrincipal("raw2", patient);
        WebSocketSession decoratedPatient = mock(WebSocketSession.class);
        when(decoratedPatient.getId()).thenReturn("raw2");
        when(decoratedPatient.isOpen()).thenReturn(true);

        WebSocketSession existingDoctor = mock(WebSocketSession.class);
        when(existingDoctor.getId()).thenReturn("raw1");
        when(existingDoctor.isOpen()).thenReturn(true);

        when(registry.roles("s1")).thenReturn(Set.of(Role.DOCTOR));
        when(registry.add("s1", Role.PATIENT, raw)).thenReturn(decoratedPatient);
        when(registry.connections("s1")).thenReturn(List.of(existingDoctor, decoratedPatient));

        handler.afterConnectionEstablished(raw);

        ArgumentCaptor<TextMessage> doctorCaptor = ArgumentCaptor.forClass(TextMessage.class);
        verify(existingDoctor).sendMessage(doctorCaptor.capture());
        assertThat(doctorCaptor.getValue().getPayload())
                .contains("\"type\":\"PEER_JOINED\"")
                .contains("\"role\":\"PATIENT\"");

        ArgumentCaptor<TextMessage> patientCaptor = ArgumentCaptor.forClass(TextMessage.class);
        verify(decoratedPatient).sendMessage(patientCaptor.capture());
        assertThat(patientCaptor.getValue().getPayload())
                .contains("\"type\":\"PEER_JOINED\"")
                .contains("\"role\":\"DOCTOR\"");
    }

    @Test
    void pingRepliesWithPong() throws Exception {
        WebSocketSession session = mock(WebSocketSession.class);
        when(session.isOpen()).thenReturn(true);

        handler.handleTextMessage(session, new TextMessage("{\"type\":\"PING\"}"));

        ArgumentCaptor<TextMessage> captor = ArgumentCaptor.forClass(TextMessage.class);
        verify(session).sendMessage(captor.capture());
        assertThat(captor.getValue().getPayload()).isEqualTo("{\"type\":\"PONG\"}");
    }

    @Test
    void nonPingMessagesAreIgnored() {
        WebSocketSession session = mock(WebSocketSession.class);

        handler.handleTextMessage(session, new TextMessage("{\"type\":\"HELLO\"}"));
        handler.handleTextMessage(session, new TextMessage("not json"));

        verifyNoInteractions(session);
    }

    @Test
    void connectionClosedRemovesFromRegistry() {
        AuthenticatedPrincipal doctor = new AuthenticatedPrincipal("s1", Role.DOCTOR, "tok");
        WebSocketSession session = sessionWithPrincipal("raw4", doctor);

        handler.afterConnectionClosed(session, CloseStatus.NORMAL);

        verify(registry).remove(session);
    }

    @Test
    void transportErrorRemovesFromRegistryAndClosesSession() throws Exception {
        WebSocketSession session = mock(WebSocketSession.class);
        when(session.isOpen()).thenReturn(true);

        handler.handleTransportError(session, new RuntimeException("boom"));

        verify(registry).remove(session);
        verify(session).close(CloseStatus.SERVER_ERROR);
    }
}

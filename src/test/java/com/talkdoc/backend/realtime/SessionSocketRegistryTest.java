package com.talkdoc.backend.realtime;

import com.talkdoc.backend.auth.Role;
import org.junit.jupiter.api.Test;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketSession;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SessionSocketRegistryTest {

    private final SessionSocketRegistry registry = new SessionSocketRegistry();

    private static WebSocketSession mockSession(String id) {
        WebSocketSession session = mock(WebSocketSession.class);
        when(session.getId()).thenReturn(id);
        when(session.isOpen()).thenReturn(true);
        return session;
    }

    @Test
    void addRegistersConnectionAndReturnsDecoratedSession() {
        WebSocketSession raw = mockSession("s1");

        WebSocketSession decorated = registry.add("session-1", Role.DOCTOR, raw);

        assertThat(decorated).isNotSameAs(raw);
        assertThat(registry.connections("session-1")).hasSize(1).contains(decorated);
        assertThat(registry.roles("session-1")).containsExactly(Role.DOCTOR);
    }

    @Test
    void unknownSessionYieldsEmptySnapshots() {
        assertThat(registry.connections("nope")).isEmpty();
        assertThat(registry.roles("nope")).isEmpty();
    }

    @Test
    void removeByRawSessionDropsConnection() {
        WebSocketSession raw = mockSession("s2");
        registry.add("session-2", Role.PATIENT, raw);

        registry.remove(raw);

        assertThat(registry.connections("session-2")).isEmpty();
        assertThat(registry.roles("session-2")).isEmpty();
    }

    @Test
    void removeByDecoratedSessionAlsoDropsConnection() {
        WebSocketSession raw = mockSession("s3");
        WebSocketSession decorated = registry.add("session-3", Role.DOCTOR, raw);

        registry.remove(decorated);

        assertThat(registry.connections("session-3")).isEmpty();
    }

    @Test
    void removeOfUnknownSessionIsANoOp() {
        WebSocketSession unknown = mockSession("ghost");

        registry.remove(unknown);

        assertThat(registry.connections("session-x")).isEmpty();
    }

    @Test
    void bothRolesTrackedIndependentlyUnderTheSameSession() {
        WebSocketSession doctorRaw = mockSession("doc");
        WebSocketSession patientRaw = mockSession("pat");

        registry.add("session-4", Role.DOCTOR, doctorRaw);
        registry.add("session-4", Role.PATIENT, patientRaw);

        assertThat(registry.roles("session-4")).containsExactlyInAnyOrder(Role.DOCTOR, Role.PATIENT);
        assertThat(registry.connections("session-4")).hasSize(2);
    }

    @Test
    void closeAllClosesEveryConnectionAndClearsRegistry() throws Exception {
        WebSocketSession doctorRaw = mockSession("doc2");
        WebSocketSession patientRaw = mockSession("pat2");
        registry.add("session-5", Role.DOCTOR, doctorRaw);
        registry.add("session-5", Role.PATIENT, patientRaw);

        registry.closeAll("session-5", CloseStatus.NORMAL);

        verify(doctorRaw).close(CloseStatus.NORMAL);
        verify(patientRaw).close(CloseStatus.NORMAL);
        assertThat(registry.connections("session-5")).isEmpty();
        assertThat(registry.roles("session-5")).isEmpty();
    }

    @Test
    void closeAllOfUnknownSessionIsANoOp() {
        registry.closeAll("never-existed", CloseStatus.NORMAL);
        // no exception; nothing to assert beyond "didn't throw".
    }
}

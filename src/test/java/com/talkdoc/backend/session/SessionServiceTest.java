package com.talkdoc.backend.session;

import com.talkdoc.backend.answer.Conversation;
import com.talkdoc.backend.answer.ConversationRepository;
import com.talkdoc.backend.common.ApiException;
import com.talkdoc.backend.common.ErrorCode;
import com.talkdoc.backend.realtime.SessionEventPublisher;
import com.talkdoc.backend.session.dto.SessionDetailResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

class SessionServiceTest {

    private SessionRepository sessionRepository;
    private ConversationRepository conversationRepository;
    private SessionEventPublisher eventPublisher;
    private SessionService sessionService;

    @BeforeEach
    void setUp() {
        sessionRepository = mock(SessionRepository.class);
        conversationRepository = mock(ConversationRepository.class);
        eventPublisher = mock(SessionEventPublisher.class);
        sessionService = new SessionService(sessionRepository, conversationRepository, eventPublisher);
    }

    @Test
    void create_issuesDistinctTokensAndPersistsViaRepository() {
        SessionService.CreatedSession created = sessionService.create();

        assertThat(created.tokens().doctorToken()).isNotBlank();
        assertThat(created.tokens().patientToken()).isNotBlank();
        assertThat(created.tokens().doctorToken()).isNotEqualTo(created.tokens().patientToken());
        assertThat(created.session().status()).isEqualTo(SessionStatus.ACTIVE);
        assertThat(created.session().currentQuestion()).isNull();
        assertThat(created.session().sessionId()).isNotBlank();

        ArgumentCaptor<Session> sessionCaptor = ArgumentCaptor.forClass(Session.class);
        ArgumentCaptor<SessionTokens> tokensCaptor = ArgumentCaptor.forClass(SessionTokens.class);
        verify(sessionRepository).create(sessionCaptor.capture(), tokensCaptor.capture());

        assertThat(sessionCaptor.getValue().sessionId()).isEqualTo(created.session().sessionId());
        assertThat(tokensCaptor.getValue()).isEqualTo(created.tokens());
    }

    @Test
    void delete_callsRepositoryDeleteThenPublisherCloseSession() {
        String sessionId = "session-1";

        sessionService.delete(sessionId);

        InOrder order = inOrder(sessionRepository, eventPublisher);
        order.verify(sessionRepository).delete(sessionId);
        order.verify(eventPublisher).closeSession(sessionId);
        verifyNoMoreInteractions(sessionRepository, eventPublisher);
    }

    @Test
    void getOrThrow_throwsSessionNotFoundWhenMissing() {
        when(sessionRepository.findById("missing")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> sessionService.getOrThrow("missing"))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).code())
                .isEqualTo(ErrorCode.SESSION_NOT_FOUND);
    }

    @Test
    void getOrThrow_returnsSessionWhenPresent() {
        Session session = new Session("s1", SessionStatus.ACTIVE, Instant.now(), null);
        when(sessionRepository.findById("s1")).thenReturn(Optional.of(session));

        assertThat(sessionService.getOrThrow("s1")).isEqualTo(session);
    }

    @Test
    void requireActive_throwsSessionClosedWhenSessionIsClosed() {
        Session closed = new Session("s1", SessionStatus.CLOSED, Instant.now(), null);
        when(sessionRepository.findById("s1")).thenReturn(Optional.of(closed));

        assertThatThrownBy(() -> sessionService.requireActive("s1"))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).code())
                .isEqualTo(ErrorCode.SESSION_CLOSED);
    }

    @Test
    void requireActive_throwsSessionNotFoundWhenMissing() {
        when(sessionRepository.findById("missing")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> sessionService.requireActive("missing"))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).code())
                .isEqualTo(ErrorCode.SESSION_NOT_FOUND);
    }

    @Test
    void detail_combinesSessionAndConversations() {
        Session session = new Session("s1", SessionStatus.ACTIVE, Instant.now(), null);
        when(sessionRepository.findById("s1")).thenReturn(Optional.of(session));
        when(conversationRepository.findAll("s1")).thenReturn(List.<Conversation>of());

        SessionDetailResponse detail = sessionService.detail("s1");

        assertThat(detail.sessionId()).isEqualTo("s1");
        assertThat(detail.status()).isEqualTo(SessionStatus.ACTIVE);
        assertThat(detail.conversations()).isEmpty();
    }
}

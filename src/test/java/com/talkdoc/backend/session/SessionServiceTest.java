package com.talkdoc.backend.session;

import com.talkdoc.backend.answer.AnswerDraft;
import com.talkdoc.backend.answer.AnswerDraftRepository;
import com.talkdoc.backend.answer.Conversation;
import com.talkdoc.backend.answer.ConversationRepository;
import com.talkdoc.backend.answer.DraftStatus;
import com.talkdoc.backend.auth.Role;
import com.talkdoc.backend.common.ApiException;
import com.talkdoc.backend.common.ErrorCode;
import com.talkdoc.backend.realtime.SessionEventPublisher;
import com.talkdoc.backend.question.Intent;
import com.talkdoc.backend.question.PendingQuestion;
import com.talkdoc.backend.session.dto.SessionDetailResponse;
import com.talkdoc.backend.sign.Recognition;
import com.talkdoc.backend.sign.RecognitionRepository;
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
    private AnswerDraftRepository draftRepository;
    private RecognitionRepository recognitionRepository;
    private SessionEventPublisher eventPublisher;
    private SessionService sessionService;

    @BeforeEach
    void setUp() {
        sessionRepository = mock(SessionRepository.class);
        conversationRepository = mock(ConversationRepository.class);
        draftRepository = mock(AnswerDraftRepository.class);
        recognitionRepository = mock(RecognitionRepository.class);
        eventPublisher = mock(SessionEventPublisher.class);
        sessionService = new SessionService(sessionRepository, conversationRepository, draftRepository,
                recognitionRepository, eventPublisher);
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

        SessionDetailResponse detail = sessionService.detail("s1", Role.DOCTOR);

        assertThat(detail.sessionId()).isEqualTo("s1");
        assertThat(detail.status()).isEqualTo(SessionStatus.ACTIVE);
        assertThat(detail.conversations()).isEmpty();
        assertThat(detail.questionVersion()).isNull();
    }

    @Test
    void detail_forDoctor_neverExposesDraftsOrRecognitions() {
        when(sessionRepository.findById("s1")).thenReturn(Optional.of(sessionWithQuestion()));
        when(conversationRepository.findAll("s1")).thenReturn(List.of());

        SessionDetailResponse detail = sessionService.detail("s1", Role.DOCTOR);

        assertThat(detail.questionVersion()).isEqualTo(2);
        assertThat(detail.drafts()).isNull();
        assertThat(detail.recognitions()).isNull();
        verifyNoMoreInteractions(draftRepository, recognitionRepository);
    }

    @Test
    void detail_forPatient_returnsOpenDraftsAndRecognitionsOfTheCurrentQuestion() {
        when(sessionRepository.findById("s1")).thenReturn(Optional.of(sessionWithQuestion()));
        when(conversationRepository.findAll("s1")).thenReturn(List.of());
        Instant now = Instant.now();
        AnswerDraft open = new AnswerDraft("d1", "q1", 2, List.of("배"), null, "배요.", 1, DraftStatus.DRAFT, now, now);
        AnswerDraft stale = new AnswerDraft("d0", "q1", 1, List.of("팔"), null, "팔이요.", 1,
                DraftStatus.INVALIDATED, now, now);
        when(draftRepository.findByQuestion("s1", "q1")).thenReturn(List.of(stale, open));
        Recognition recognition = new Recognition("r1", "q1", 2, "배", 0.9, true, null, "mock", "req", now);
        when(recognitionRepository.findByQuestion("s1", "q1", 2)).thenReturn(List.of(recognition));

        SessionDetailResponse detail = sessionService.detail("s1", Role.PATIENT);

        assertThat(detail.drafts()).containsExactly(open);
        assertThat(detail.recognitions()).containsExactly(recognition);
    }

    private static Session sessionWithQuestion() {
        PendingQuestion question = new PendingQuestion("q1", "어디가 아프세요?", List.of(Intent.BODY_LOCATION),
                List.of("배"), Instant.now(), 2, Instant.now());
        return new Session("s1", SessionStatus.ACTIVE, Instant.now(), question);
    }
}

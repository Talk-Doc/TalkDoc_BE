package com.talkdoc.backend.question;

import com.talkdoc.backend.ai.LlmClient;
import com.talkdoc.backend.ai.SttClient;
import com.talkdoc.backend.ai.model.IntentAnalysis;
import com.talkdoc.backend.answer.AnswerDraft;
import com.talkdoc.backend.answer.AnswerDraftRepository;
import com.talkdoc.backend.answer.Conversation;
import com.talkdoc.backend.answer.ConversationRepository;
import com.talkdoc.backend.answer.DraftStatus;
import com.talkdoc.backend.common.ApiException;
import com.talkdoc.backend.common.ErrorCode;
import com.talkdoc.backend.question.dto.QuestionResponse;
import com.talkdoc.backend.realtime.EventType;
import com.talkdoc.backend.realtime.SessionEvent;
import com.talkdoc.backend.realtime.SessionEventPublisher;
import com.talkdoc.backend.session.Session;
import com.talkdoc.backend.session.SessionRepository;
import com.talkdoc.backend.session.SessionService;
import com.talkdoc.backend.session.SessionStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class QuestionServiceTest {

    private SessionService sessionService;
    private SessionRepository sessionRepository;
    private ConversationRepository conversationRepository;
    private AnswerDraftRepository draftRepository;
    private SttClient sttClient;
    private LlmClient llmClient;
    private AnswerModeResolver answerModeResolver;
    private SessionEventPublisher eventPublisher;
    private QuestionService questionService;

    private static final String SESSION_ID = "session-1";

    @BeforeEach
    void setUp() {
        sessionService = mock(SessionService.class);
        sessionRepository = mock(SessionRepository.class);
        conversationRepository = mock(ConversationRepository.class);
        draftRepository = mock(AnswerDraftRepository.class);
        sttClient = mock(SttClient.class);
        llmClient = mock(LlmClient.class);
        answerModeResolver = new StaticAnswerModeResolver();
        eventPublisher = mock(SessionEventPublisher.class);
        questionService = new QuestionService(sessionService, sessionRepository, conversationRepository,
                draftRepository, sttClient, llmClient, answerModeResolver, eventPublisher);

        Session activeSession = new Session(SESSION_ID, SessionStatus.ACTIVE, Instant.now(), null);
        when(sessionService.requireActive(SESSION_ID)).thenReturn(activeSession);
        when(conversationRepository.findAll(SESSION_ID)).thenReturn(List.of());
        when(draftRepository.findByQuestion(eq(SESSION_ID), anyString())).thenReturn(List.of());
    }

    @Test
    void postQuestion_withText_skipsSttAndUsesTextDirectly() {
        when(llmClient.analyzeIntent("어디가 아파서 오셨어요?")).thenReturn(IntentAnalysis.of(Intent.SYMPTOM));

        QuestionResponse response = questionService.postQuestion(SESSION_ID, null, "어디가 아파서 오셨어요?");

        assertThat(response.text()).isEqualTo("어디가 아파서 오셨어요?");
        assertThat(response.intent()).isEqualTo(Intent.SYMPTOM);
        assertThat(response.intents()).containsExactly(Intent.SYMPTOM);
        assertThat(response.candidates()).isEqualTo(Intent.candidatesFor(List.of(Intent.SYMPTOM)));
        assertThat(response.supported()).isTrue();
        assertThat(response.questionId()).isNotBlank();
        assertThat(response.askedAt()).isNotNull();

        verify(sttClient, never()).transcribe(any(), anyString());
    }

    @Test
    void postQuestion_withBlankTextAndNoAudio_throwsInvalidRequest() {
        assertThatThrownBy(() -> questionService.postQuestion(SESSION_ID, null, "   "))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).code())
                .isEqualTo(ErrorCode.INVALID_REQUEST);
    }

    @Test
    void postQuestion_withValidAudio_transcribesAndAnalyzes() throws Exception {
        MultipartFile audio = new MockMultipartFile("audio", "q.wav", "audio/wav", "bytes".getBytes());
        when(sttClient.transcribe(eq("bytes".getBytes()), eq("audio/wav"))).thenReturn("복용 중인 약이 있나요?");
        when(llmClient.analyzeIntent("복용 중인 약이 있나요?")).thenReturn(IntentAnalysis.of(Intent.HISTORY_STATE));

        QuestionResponse response = questionService.postQuestion(SESSION_ID, audio, null);

        assertThat(response.text()).isEqualTo("복용 중인 약이 있나요?");
        assertThat(response.intents()).containsExactly(Intent.HISTORY_STATE);
    }

    @Test
    void postQuestion_acceptsVideoWebmAudioContentType() throws Exception {
        MultipartFile audio = new MockMultipartFile("audio", "q.webm", "video/webm", "bytes".getBytes());
        when(sttClient.transcribe(eq("bytes".getBytes()), eq("video/webm"))).thenReturn("질문");
        when(llmClient.analyzeIntent("질문")).thenReturn(IntentAnalysis.unsupported());

        QuestionResponse response = questionService.postQuestion(SESSION_ID, audio, null);

        assertThat(response.text()).isEqualTo("질문");
        assertThat(response.supported()).isFalse();
    }

    @Test
    void postQuestion_withUnsupportedContentType_throwsUnsupportedMedia() {
        MultipartFile audio = new MockMultipartFile("audio", "q.txt", "text/plain", "bytes".getBytes());

        assertThatThrownBy(() -> questionService.postQuestion(SESSION_ID, audio, null))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).code())
                .isEqualTo(ErrorCode.UNSUPPORTED_MEDIA);
    }

    @Test
    void postQuestion_whenSttReturnsBlank_throwsSttFailed() throws Exception {
        MultipartFile audio = new MockMultipartFile("audio", "q.wav", "audio/wav", "bytes".getBytes());
        when(sttClient.transcribe(any(), anyString())).thenReturn("   ");

        assertThatThrownBy(() -> questionService.postQuestion(SESSION_ID, audio, null))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).code())
                .isEqualTo(ErrorCode.STT_FAILED);
    }

    @Test
    void postQuestion_whenLlmReturnsNoIntents_defaultsToOther() {
        when(llmClient.analyzeIntent(anyString())).thenReturn(new IntentAnalysis(List.of()));

        QuestionResponse response = questionService.postQuestion(SESSION_ID, null, "알 수 없는 질문");

        assertThat(response.intents()).containsExactly(Intent.OTHER);
        assertThat(response.intent()).isEqualTo(Intent.OTHER);
        assertThat(response.supported()).isFalse();
        assertThat(response.candidates()).isEmpty();
    }

    @Test
    void postQuestion_updatesCurrentQuestionAndPublishesEvent() {
        when(llmClient.analyzeIntent(anyString())).thenReturn(IntentAnalysis.of(Intent.BODY_LOCATION));

        questionService.postQuestion(SESSION_ID, null, "어디가 아프세요?");

        ArgumentCaptor<PendingQuestion> questionCaptor = ArgumentCaptor.forClass(PendingQuestion.class);
        verify(sessionRepository).updateCurrentQuestion(eq(SESSION_ID), questionCaptor.capture());
        assertThat(questionCaptor.getValue().text()).isEqualTo("어디가 아프세요?");

        ArgumentCaptor<SessionEvent> eventCaptor = ArgumentCaptor.forClass(SessionEvent.class);
        verify(eventPublisher).publish(eventCaptor.capture());
        assertThat(eventCaptor.getValue().type()).isEqualTo(EventType.QUESTION_POSTED);
        assertThat(eventCaptor.getValue().sessionId()).isEqualTo(SESSION_ID);
        assertThat(eventCaptor.getValue().payload()).isEqualTo(questionCaptor.getValue());
    }

    @Test
    void postQuestion_signRequiredIntent_hasNoCardOptions() {
        when(llmClient.analyzeIntent(anyString())).thenReturn(IntentAnalysis.of(Intent.BODY_LOCATION));

        QuestionResponse response = questionService.postQuestion(SESSION_ID, null, "어디가 아프세요?");

        assertThat(response.answerMode()).isEqualTo(AnswerMode.SIGN_REQUIRED);
        assertThat(response.cardOptions()).isEmpty();
    }

    @Test
    void postQuestion_durationIntent_returnsCardOptions() {
        when(llmClient.analyzeIntent("언제부터 아팠어요?")).thenReturn(IntentAnalysis.of(Intent.DURATION));

        QuestionResponse response = questionService.postQuestion(SESSION_ID, null, "언제부터 아팠어요?");

        assertThat(response.intent()).isEqualTo(Intent.DURATION);
        assertThat(response.answerMode()).isEqualTo(AnswerMode.CARD_SELECT);
        assertThat(response.cardOptions()).containsExactly("오늘부터", "어제부터", "2~3일 전부터", "1주일 전부터", "2주 이상", "한 달 이상");
        assertThat(response.candidates()).isEmpty();
    }

    @Test
    void postQuestion_choiceIntent_usesLlmGeneratedCards() {
        when(llmClient.analyzeIntent("어느 쪽 다리가 아파요?"))
                .thenReturn(IntentAnalysis.choice(List.of("왼쪽", "오른쪽", "양쪽")));

        QuestionResponse response = questionService.postQuestion(SESSION_ID, null, "어느 쪽 다리가 아파요?");

        assertThat(response.intent()).isEqualTo(Intent.CHOICE);
        assertThat(response.answerMode()).isEqualTo(AnswerMode.CARD_SELECT);
        assertThat(response.cardOptions()).containsExactly("왼쪽", "오른쪽", "양쪽");
        assertThat(response.candidates()).isEmpty();
    }

    @Test
    void postQuestion_choiceWithoutEnoughCards_fallsBackToOther() {
        when(llmClient.analyzeIntent("뭐라고요?")).thenReturn(IntentAnalysis.choice(List.of("네")));

        QuestionResponse response = questionService.postQuestion(SESSION_ID, null, "뭐라고요?");

        assertThat(response.intents()).containsExactly(Intent.OTHER);
        assertThat(response.answerMode()).isEqualTo(AnswerMode.SIGN_REQUIRED);
        assertThat(response.cardOptions()).isEmpty();
        assertThat(response.supported()).isFalse();
    }

    // ---- updateQuestion ------------------------------------------------------------------------

    private static final String QUESTION_ID = "question-1";

    /** Makes the session return a pending question at the given version. */
    private PendingQuestion pending(int version) {
        PendingQuestion question = new PendingQuestion(QUESTION_ID, "어디가 아프세요?",
                List.of(Intent.BODY_LOCATION), Intent.candidatesFor(List.of(Intent.BODY_LOCATION)),
                AnswerMode.SIGN_REQUIRED, List.of(),
                Instant.parse("2024-01-01T00:00:00Z"), version, null);
        when(sessionService.requireActive(SESSION_ID))
                .thenReturn(new Session(SESSION_ID, SessionStatus.ACTIVE, Instant.now(), question));
        return question;
    }

    @Test
    void postQuestion_startsAtVersionOneWithNoUpdatedAt() {
        when(llmClient.analyzeIntent(anyString())).thenReturn(IntentAnalysis.of(Intent.BODY_LOCATION));

        QuestionResponse response = questionService.postQuestion(SESSION_ID, null, "어디가 아프세요?");

        assertThat(response.version()).isEqualTo(1);
        assertThat(response.updatedAt()).isNull();
    }

    @Test
    void updateQuestion_incrementsVersionKeepsIdAndPublishesQuestionUpdated() {
        PendingQuestion current = pending(1);
        when(llmClient.analyzeIntent("어떤 증상이 있으세요?")).thenReturn(IntentAnalysis.of(Intent.SYMPTOM));

        QuestionResponse response = questionService.updateQuestion(SESSION_ID, QUESTION_ID, "어떤 증상이 있으세요?", 1);

        assertThat(response.questionId()).isEqualTo(QUESTION_ID);
        assertThat(response.version()).isEqualTo(2);
        assertThat(response.text()).isEqualTo("어떤 증상이 있으세요?");
        assertThat(response.intents()).containsExactly(Intent.SYMPTOM);
        assertThat(response.candidates()).isEqualTo(Intent.candidatesFor(List.of(Intent.SYMPTOM)));
        assertThat(response.askedAt()).isEqualTo(current.askedAt());
        assertThat(response.updatedAt()).isNotNull();

        ArgumentCaptor<PendingQuestion> saved = ArgumentCaptor.forClass(PendingQuestion.class);
        verify(sessionRepository).updateCurrentQuestion(eq(SESSION_ID), saved.capture());
        assertThat(saved.getValue().version()).isEqualTo(2);

        ArgumentCaptor<SessionEvent> event = ArgumentCaptor.forClass(SessionEvent.class);
        verify(eventPublisher).publish(event.capture());
        assertThat(event.getValue().type()).isEqualTo(EventType.QUESTION_UPDATED);
        assertThat(event.getValue().payload()).isEqualTo(saved.getValue());
    }

    @Test
    void updateQuestion_invalidatesOlderOpenDraftsButKeepsOthers() {
        pending(1);
        when(llmClient.analyzeIntent(anyString())).thenReturn(IntentAnalysis.of(Intent.SYMPTOM));
        Instant now = Instant.now();
        AnswerDraft open = new AnswerDraft("d1", QUESTION_ID, 1, List.of("배"), null, "배요.", 1,
                DraftStatus.DRAFT, now, now);
        AnswerDraft confirmed = new AnswerDraft("d2", QUESTION_ID, 1, List.of("배"), null, "배요.", 1,
                DraftStatus.CONFIRMED, now, now);
        when(draftRepository.findByQuestion(SESSION_ID, QUESTION_ID)).thenReturn(List.of(open, confirmed));

        questionService.updateQuestion(SESSION_ID, QUESTION_ID, "어떤 증상이 있으세요?", 1);

        ArgumentCaptor<AnswerDraft> saved = ArgumentCaptor.forClass(AnswerDraft.class);
        verify(draftRepository).save(eq(SESSION_ID), saved.capture());
        assertThat(saved.getValue().answerId()).isEqualTo("d1");
        assertThat(saved.getValue().status()).isEqualTo(DraftStatus.INVALIDATED);
    }

    @Test
    void updateQuestion_withStaleVersion_throwsVersionConflict() {
        pending(2);

        assertThatThrownBy(() -> questionService.updateQuestion(SESSION_ID, QUESTION_ID, "다시 묻습니다", 1))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> {
                    assertThat(((ApiException) e).code()).isEqualTo(ErrorCode.VERSION_CONFLICT);
                    assertThat(e).hasMessageContaining("현재 버전: 2");
                });
        verify(sessionRepository, never()).updateCurrentQuestion(anyString(), any());
    }

    @Test
    void updateQuestion_whenQuestionAlreadyConfirmed_throwsQuestionAlreadyAnswered() {
        when(conversationRepository.findAll(SESSION_ID)).thenReturn(List.of(
                Conversation.confirmed("a1", QUESTION_ID, "어디가 아프세요?", List.of(Intent.BODY_LOCATION),
                        List.of("배"), "배요.", Instant.now(), 1)));

        assertThatThrownBy(() -> questionService.updateQuestion(SESSION_ID, QUESTION_ID, "다시 묻습니다", 1))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).code())
                .isEqualTo(ErrorCode.QUESTION_ALREADY_ANSWERED);
    }

    @Test
    void updateQuestion_whenNoSuchPendingQuestion_throwsQuestionNotFound() {
        pending(1);

        assertThatThrownBy(() -> questionService.updateQuestion(SESSION_ID, "other-question", "다시 묻습니다", 1))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).code())
                .isEqualTo(ErrorCode.QUESTION_NOT_FOUND);
    }
}

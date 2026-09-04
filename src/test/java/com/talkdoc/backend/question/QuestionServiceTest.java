package com.talkdoc.backend.question;

import com.talkdoc.backend.ai.LlmClient;
import com.talkdoc.backend.ai.SttClient;
import com.talkdoc.backend.ai.model.IntentAnalysis;
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
    private SttClient sttClient;
    private LlmClient llmClient;
    private SessionEventPublisher eventPublisher;
    private QuestionService questionService;

    private static final String SESSION_ID = "session-1";

    @BeforeEach
    void setUp() {
        sessionService = mock(SessionService.class);
        sessionRepository = mock(SessionRepository.class);
        sttClient = mock(SttClient.class);
        llmClient = mock(LlmClient.class);
        eventPublisher = mock(SessionEventPublisher.class);
        questionService = new QuestionService(sessionService, sessionRepository, sttClient, llmClient, eventPublisher);

        Session activeSession = new Session(SESSION_ID, SessionStatus.ACTIVE, Instant.now(), null);
        when(sessionService.requireActive(SESSION_ID)).thenReturn(activeSession);
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
}

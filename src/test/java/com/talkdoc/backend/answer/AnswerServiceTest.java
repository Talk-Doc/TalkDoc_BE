package com.talkdoc.backend.answer;

import com.talkdoc.backend.ai.LlmClient;
import com.talkdoc.backend.ai.TtsClient;
import com.talkdoc.backend.answer.dto.ConfirmRequest;
import com.talkdoc.backend.answer.dto.PreviewRequest;
import com.talkdoc.backend.answer.dto.PreviewResponse;
import com.talkdoc.backend.auth.Role;
import com.talkdoc.backend.common.ApiException;
import com.talkdoc.backend.common.ErrorCode;
import com.talkdoc.backend.question.AnswerMode;
import com.talkdoc.backend.question.Intent;
import com.talkdoc.backend.question.PendingQuestion;
import com.talkdoc.backend.realtime.EventType;
import com.talkdoc.backend.realtime.SessionEvent;
import com.talkdoc.backend.realtime.SessionEventPublisher;
import com.talkdoc.backend.session.Session;
import com.talkdoc.backend.session.SessionRepository;
import com.talkdoc.backend.session.SessionService;
import com.talkdoc.backend.session.SessionStatus;
import com.talkdoc.backend.sign.Recognition;
import com.talkdoc.backend.sign.RecognitionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AnswerServiceTest {

    private static final String SESSION_ID = "session-1";
    private static final String QUESTION_ID = "question-1";

    private SessionService sessionService;
    private SessionRepository sessionRepository;
    private ConversationRepository conversationRepository;
    private AnswerDraftRepository draftRepository;
    private RecognitionRepository recognitionRepository;
    private LlmClient llmClient;
    private SessionEventPublisher eventPublisher;
    private AnswerService answerService;

    /** In-memory stand-in for the drafts hash so save/findById round-trip like the real repository. */
    private final Map<String, AnswerDraft> drafts = new HashMap<>();

    @BeforeEach
    void setUp() {
        sessionService = mock(SessionService.class);
        sessionRepository = mock(SessionRepository.class);
        conversationRepository = mock(ConversationRepository.class);
        draftRepository = mock(AnswerDraftRepository.class);
        recognitionRepository = mock(RecognitionRepository.class);
        llmClient = mock(LlmClient.class);
        TtsClient ttsClient = mock(TtsClient.class);
        eventPublisher = mock(SessionEventPublisher.class);
        answerService = new AnswerService(sessionService, sessionRepository, conversationRepository,
                draftRepository, recognitionRepository, llmClient, ttsClient, eventPublisher);

        pendingQuestion(1);
        when(conversationRepository.findAll(SESSION_ID)).thenReturn(List.of());
        when(llmClient.composeAnswer(anyString(), anyList(), anyList())).thenReturn("배가 아파요.");

        drafts.clear();
        org.mockito.Mockito.doAnswer(inv -> {
            AnswerDraft draft = inv.getArgument(1);
            drafts.put(draft.answerId(), draft);
            return null;
        }).when(draftRepository).save(eq(SESSION_ID), any(AnswerDraft.class));
        when(draftRepository.findById(eq(SESSION_ID), anyString()))
                .thenAnswer(inv -> Optional.ofNullable(drafts.get(inv.<String>getArgument(1))));
        when(draftRepository.findByQuestion(eq(SESSION_ID), anyString()))
                .thenAnswer(inv -> drafts.values().stream()
                        .filter(d -> d.questionId().equals(inv.<String>getArgument(1)))
                        .toList());
    }

    /** Points the session at a pending question of the given version. */
    private PendingQuestion pendingQuestion(int version) {
        PendingQuestion question = new PendingQuestion(QUESTION_ID, "어디가 아프세요?",
                List.of(Intent.BODY_LOCATION, Intent.SYMPTOM),
                Intent.candidatesFor(List.of(Intent.BODY_LOCATION, Intent.SYMPTOM)),
                AnswerMode.SIGN_REQUIRED, List.of(),
                Instant.parse("2024-01-01T00:00:00Z"), version, null);
        when(sessionService.requireActive(SESSION_ID))
                .thenReturn(new Session(SESSION_ID, SessionStatus.ACTIVE, Instant.now(), question));
        return question;
    }

    private Recognition recognition(String id, String label, boolean accepted) {
        return recognition(id, label, accepted, QUESTION_ID);
    }

    private Recognition recognition(String id, String label, boolean accepted, String questionId) {
        Recognition recognition = new Recognition(id, questionId, 1, label, 0.9, accepted,
                accepted ? null : "LOW_CONFIDENCE", "mock", "req-" + id, Instant.now());
        when(recognitionRepository.findById(SESSION_ID, id)).thenReturn(Optional.of(recognition));
        return recognition;
    }

    // ---- preview ------------------------------------------------------------------------------

    @Test
    void preview_savesADraftAndReturnsItsIdAndVersion() {
        PreviewResponse response = answerService.preview(SESSION_ID,
                new PreviewRequest(List.of("배", "아프다"), null, null, null));

        assertThat(response.questionId()).isEqualTo(QUESTION_ID);
        assertThat(response.questionVersion()).isEqualTo(1);
        assertThat(response.labels()).containsExactly("배", "아프다");
        assertThat(response.answer()).isEqualTo("배가 아파요.");
        assertThat(response.answerId()).isNotBlank();
        assertThat(response.version()).isEqualTo(1);
        assertThat(response.recognitionIds()).isNull();

        AnswerDraft saved = drafts.get(response.answerId());
        assertThat(saved.status()).isEqualTo(DraftStatus.DRAFT);
        assertThat(saved.questionVersion()).isEqualTo(1);
        assertThat(saved.labels()).containsExactly("배", "아프다");
    }

    @Test
    void preview_withRecognitionIds_resolvesLabelsInTheGivenOrder() {
        recognition("r1", "배", true);
        recognition("r2", "아프다", true);

        PreviewResponse response = answerService.preview(SESSION_ID,
                new PreviewRequest(List.of("무시됨"), QUESTION_ID, 1, List.of("r2", "r1")));

        assertThat(response.labels()).containsExactly("아프다", "배");
        assertThat(response.recognitionIds()).containsExactly("r2", "r1");
        verify(llmClient).composeAnswer(eq("어디가 아프세요?"), eq(List.of("아프다", "배")), anyList());
    }

    @Test
    void preview_withUnacceptedRecognition_isRejected() {
        recognition("r1", "배", false);

        assertThatThrownBy(() -> answerService.preview(SESSION_ID,
                new PreviewRequest(null, null, null, List.of("r1"))))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> {
                    assertThat(((ApiException) e).code()).isEqualTo(ErrorCode.INVALID_REQUEST);
                    assertThat(e).hasMessageContaining("확인되지 않은 인식 결과");
                });
    }

    @Test
    void preview_withRecognitionOfAnotherQuestion_isRejected() {
        recognition("r1", "배", true, "other-question");

        assertThatThrownBy(() -> answerService.preview(SESSION_ID,
                new PreviewRequest(null, null, null, List.of("r1"))))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(e).hasMessageContaining("다른 질문의 인식 결과"));
    }

    @Test
    void preview_withUnknownRecognitionId_throwsRecognitionNotFound() {
        when(recognitionRepository.findById(SESSION_ID, "nope")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> answerService.preview(SESSION_ID,
                new PreviewRequest(null, null, null, List.of("nope"))))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).code())
                .isEqualTo(ErrorCode.RECOGNITION_NOT_FOUND);
    }

    @Test
    void preview_withNeitherLabelsNorRecognitionIds_throwsInvalidRequest() {
        assertThatThrownBy(() -> answerService.preview(SESSION_ID, new PreviewRequest(List.of(), null, null, List.of())))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).code())
                .isEqualTo(ErrorCode.INVALID_REQUEST);
    }

    @Test
    void preview_withStaleQuestionVersion_throwsVersionConflict() {
        pendingQuestion(2);

        assertThatThrownBy(() -> answerService.preview(SESSION_ID,
                new PreviewRequest(List.of("배"), QUESTION_ID, 1, null)))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).code())
                .isEqualTo(ErrorCode.VERSION_CONFLICT);
    }

    @Test
    void preview_withAnotherQuestionId_throwsQuestionNotFound() {
        assertThatThrownBy(() -> answerService.preview(SESSION_ID,
                new PreviewRequest(List.of("배"), "other-question", null, null)))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).code())
                .isEqualTo(ErrorCode.QUESTION_NOT_FOUND);
    }

    // ---- confirm ------------------------------------------------------------------------------

    @Test
    void confirm_withAnswerId_appendsConversationAndMarksDraftConfirmed() {
        String answerId = previewAnswerId();

        AnswerService.ConfirmResult result = answerService.confirm(SESSION_ID,
                new ConfirmRequest(null, null, answerId, 1));

        assertThat(result.created()).isTrue();
        assertThat(result.conversation().answerId()).isEqualTo(answerId);
        assertThat(result.conversation().answer()).isEqualTo("배가 아파요.");
        assertThat(result.conversation().questionVersion()).isEqualTo(1);
        assertThat(result.conversation().version()).isEqualTo(1);
        assertThat(result.conversation().signs()).containsExactly("배", "아프다");

        verify(conversationRepository).append(eq(SESSION_ID), any(Conversation.class));
        assertThat(drafts.get(answerId).status()).isEqualTo(DraftStatus.CONFIRMED);
        verify(sessionRepository).updateCurrentQuestion(SESSION_ID, null);

        ArgumentCaptor<SessionEvent> event = ArgumentCaptor.forClass(SessionEvent.class);
        verify(eventPublisher).publish(event.capture());
        assertThat(event.getValue().type()).isEqualTo(EventType.ANSWER_CONFIRMED);
    }

    @Test
    void confirm_withTheSameAnswerIdTwice_isIdempotent() {
        String answerId = previewAnswerId();
        AnswerService.ConfirmResult first = answerService.confirm(SESSION_ID,
                new ConfirmRequest(null, null, answerId, null));
        when(conversationRepository.findById(SESSION_ID, answerId)).thenReturn(Optional.of(first.conversation()));

        AnswerService.ConfirmResult second = answerService.confirm(SESSION_ID,
                new ConfirmRequest(null, null, answerId, null));

        assertThat(second.created()).isFalse();
        assertThat(second.conversation()).isEqualTo(first.conversation());
        verify(conversationRepository, times(1)).append(eq(SESSION_ID), any(Conversation.class));
        verify(eventPublisher, times(1)).publish(any(SessionEvent.class));
    }

    @Test
    void confirm_afterTheQuestionWasUpdated_throwsDraftInvalidated() {
        String answerId = previewAnswerId();
        pendingQuestion(2); // doctor edited the question in the meantime

        assertThatThrownBy(() -> answerService.confirm(SESSION_ID, new ConfirmRequest(null, null, answerId, null)))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> {
                    assertThat(((ApiException) e).code()).isEqualTo(ErrorCode.DRAFT_INVALIDATED);
                    assertThat(e).hasMessageContaining("질문이 변경되어");
                });

        assertThat(drafts.get(answerId).status()).isEqualTo(DraftStatus.INVALIDATED);
        verify(conversationRepository, never()).append(anyString(), any());
    }

    @Test
    void confirm_withStaleDraftVersion_throwsVersionConflict() {
        String answerId = previewAnswerId();

        assertThatThrownBy(() -> answerService.confirm(SESSION_ID, new ConfirmRequest(null, null, answerId, 7)))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> {
                    assertThat(((ApiException) e).code()).isEqualTo(ErrorCode.VERSION_CONFLICT);
                    assertThat(e).hasMessageContaining("현재 버전: 1");
                });
    }

    @Test
    void confirm_withUnknownAnswerId_throwsDraftNotFound() {
        assertThatThrownBy(() -> answerService.confirm(SESSION_ID, new ConfirmRequest(null, null, "nope", null)))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).code())
                .isEqualTo(ErrorCode.DRAFT_NOT_FOUND);
    }

    @Test
    void confirm_legacyLabelsAndAnswer_stillWorksAndInvalidatesOpenDrafts() {
        String openDraftId = previewAnswerId();

        AnswerService.ConfirmResult result = answerService.confirm(SESSION_ID,
                new ConfirmRequest(List.of("배", "아프다"), "배가 조금 아파요.", null, null));

        assertThat(result.created()).isTrue();
        assertThat(result.conversation().answerId()).isNotBlank().isNotEqualTo(openDraftId);
        assertThat(result.conversation().answer()).isEqualTo("배가 조금 아파요.");
        assertThat(result.conversation().questionVersion()).isEqualTo(1);
        assertThat(result.conversation().version()).isEqualTo(1);
        verify(sessionRepository).updateCurrentQuestion(SESSION_ID, null);
        assertThat(drafts.get(openDraftId).status()).isEqualTo(DraftStatus.INVALIDATED);
    }

    // ---- edit ---------------------------------------------------------------------------------

    @Test
    void updateAnswer_asDoctor_onlyRecordsAPendingEditAndPublishesEditProposed() {
        Conversation existing = existingConversation();

        Conversation updated = answerService.updateAnswer(SESSION_ID, "a1", "배가 많이 아파요.", 1, Role.DOCTOR);

        assertThat(updated.answer()).isEqualTo(existing.answer());
        assertThat(updated.version()).isEqualTo(1);
        assertThat(updated.editedBy()).isNull();
        assertThat(updated.pendingEdit()).isNotNull();
        assertThat(updated.pendingEdit().answer()).isEqualTo("배가 많이 아파요.");
        assertThat(updated.pendingEdit().proposedBy()).isEqualTo(Role.DOCTOR);

        ArgumentCaptor<SessionEvent> event = ArgumentCaptor.forClass(SessionEvent.class);
        verify(eventPublisher).publish(event.capture());
        assertThat(event.getValue().type()).isEqualTo(EventType.ANSWER_EDIT_PROPOSED);
    }

    @Test
    void updateAnswer_asPatient_appliesTheEditAndBumpsTheVersion() {
        existingConversation();

        Conversation updated = answerService.updateAnswer(SESSION_ID, "a1", "  배가 많이 아파요.  ", 1, Role.PATIENT);

        assertThat(updated.answer()).isEqualTo("배가 많이 아파요.");
        assertThat(updated.version()).isEqualTo(2);
        assertThat(updated.editedBy()).isEqualTo(Role.PATIENT);
        assertThat(updated.editedAt()).isNotNull();
        assertThat(updated.pendingEdit()).isNull();

        ArgumentCaptor<SessionEvent> event = ArgumentCaptor.forClass(SessionEvent.class);
        verify(eventPublisher).publish(event.capture());
        assertThat(event.getValue().type()).isEqualTo(EventType.ANSWER_UPDATED);
    }

    @Test
    void updateAnswer_withStaleVersion_throwsVersionConflict() {
        existingConversation();

        assertThatThrownBy(() -> answerService.updateAnswer(SESSION_ID, "a1", "바꿀래요.", 5, Role.PATIENT))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> {
                    assertThat(((ApiException) e).code()).isEqualTo(ErrorCode.VERSION_CONFLICT);
                    assertThat(e).hasMessageContaining("현재 버전: 1");
                });
        verify(conversationRepository, never()).update(anyString(), any());
    }

    @Test
    void updateAnswer_withUnknownAnswerId_throwsAnswerNotFound() {
        when(conversationRepository.findById(SESSION_ID, "nope")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> answerService.updateAnswer(SESSION_ID, "nope", "바꿀래요.", null, Role.PATIENT))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).code())
                .isEqualTo(ErrorCode.ANSWER_NOT_FOUND);
    }

    // ---- helpers ------------------------------------------------------------------------------

    private String previewAnswerId() {
        return answerService.preview(SESSION_ID, new PreviewRequest(List.of("배", "아프다"), null, null, null))
                .answerId();
    }

    private Conversation existingConversation() {
        Conversation conversation = Conversation.confirmed("a1", QUESTION_ID, "어디가 아프세요?",
                List.of(Intent.BODY_LOCATION), List.of("배", "아프다"), "배가 아파요.", Instant.now(), 1);
        when(conversationRepository.findById(SESSION_ID, "a1")).thenReturn(Optional.of(conversation));
        when(conversationRepository.update(eq(SESSION_ID), any(Conversation.class)))
                .thenAnswer(inv -> Optional.of(inv.getArgument(1)));
        return conversation;
    }
}

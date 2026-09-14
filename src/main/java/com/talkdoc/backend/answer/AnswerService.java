package com.talkdoc.backend.answer;

import com.talkdoc.backend.ai.LlmClient;
import com.talkdoc.backend.ai.TtsClient;
import com.talkdoc.backend.ai.model.TtsAudio;
import com.talkdoc.backend.answer.dto.ConfirmRequest;
import com.talkdoc.backend.answer.dto.PreviewRequest;
import com.talkdoc.backend.answer.dto.PreviewResponse;
import com.talkdoc.backend.auth.Role;
import com.talkdoc.backend.common.ApiException;
import com.talkdoc.backend.common.ErrorCode;
import com.talkdoc.backend.common.IdGenerator;
import com.talkdoc.backend.question.Intent;
import com.talkdoc.backend.question.PendingQuestion;
import com.talkdoc.backend.realtime.EventType;
import com.talkdoc.backend.realtime.SessionEvent;
import com.talkdoc.backend.realtime.SessionEventPublisher;
import com.talkdoc.backend.session.Session;
import com.talkdoc.backend.session.SessionRepository;
import com.talkdoc.backend.session.SessionService;
import com.talkdoc.backend.sign.Recognition;
import com.talkdoc.backend.sign.RecognitionRepository;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Turns recognised sign labels into a confirmed answer: preview (stored as an {@link AnswerDraft}),
 * confirm (appended to the conversation, clears the pending question), edit an already-confirmed
 * answer, and text-to-speech playback for the doctor.
 *
 * <p>Everything is version-checked against the question the patient was actually answering: if the
 * doctor edits the question after a preview, the draft is invalidated rather than silently confirmed
 * against a question the patient never saw.</p>
 */
@Service
public class AnswerService {

    private final SessionService sessionService;
    private final SessionRepository sessionRepository;
    private final ConversationRepository conversationRepository;
    private final AnswerDraftRepository draftRepository;
    private final RecognitionRepository recognitionRepository;
    private final LlmClient llmClient;
    private final TtsClient ttsClient;
    private final SessionEventPublisher eventPublisher;

    public AnswerService(SessionService sessionService,
                          SessionRepository sessionRepository,
                          ConversationRepository conversationRepository,
                          AnswerDraftRepository draftRepository,
                          RecognitionRepository recognitionRepository,
                          LlmClient llmClient,
                          TtsClient ttsClient,
                          SessionEventPublisher eventPublisher) {
        this.sessionService = sessionService;
        this.sessionRepository = sessionRepository;
        this.conversationRepository = conversationRepository;
        this.draftRepository = draftRepository;
        this.recognitionRepository = recognitionRepository;
        this.llmClient = llmClient;
        this.ttsClient = ttsClient;
        this.eventPublisher = eventPublisher;
    }

    /** A confirmation outcome: {@code created} is false when an already-confirmed draft was replayed. */
    public record ConfirmResult(Conversation conversation, boolean created) {
    }

    // ---- preview ------------------------------------------------------------------------------

    public PreviewResponse preview(String sessionId, PreviewRequest request) {
        Session session = sessionService.requireActive(sessionId);
        PendingQuestion question = requireCurrentQuestion(session);

        List<String> recognitionIds = request.recognitionIdsOrEmpty();
        List<String> labels = request.labelsOrEmpty();
        if (labels.isEmpty() && recognitionIds.isEmpty()) {
            throw new ApiException(ErrorCode.INVALID_REQUEST, "labels 또는 recognition_ids 중 하나는 필수입니다.");
        }
        requireQuestionMatches(question, request.questionId(), request.questionVersion());

        List<String> resolvedRecognitionIds = null;
        if (!recognitionIds.isEmpty()) {
            labels = resolveLabels(sessionId, question, recognitionIds);
            resolvedRecognitionIds = List.copyOf(recognitionIds);
        }
        validateLabels(labels);

        List<Conversation> prior = conversationRepository.findAll(sessionId);
        String answer = llmClient.composeAnswer(question.text(), labels, prior);

        Instant now = Instant.now();
        AnswerDraft draft = new AnswerDraft(
                IdGenerator.uuid(),
                question.questionId(),
                question.version(),
                labels,
                resolvedRecognitionIds,
                answer,
                1,
                DraftStatus.DRAFT,
                now,
                now);
        draftRepository.save(sessionId, draft);

        return new PreviewResponse(question.questionId(), question.version(), labels, answer,
                draft.answerId(), draft.version(), resolvedRecognitionIds);
    }

    /** Labels of the referenced recognitions, in the order the client listed them. */
    private List<String> resolveLabels(String sessionId, PendingQuestion question, List<String> recognitionIds) {
        List<String> labels = new ArrayList<>(recognitionIds.size());
        for (String recognitionId : recognitionIds) {
            Recognition recognition = recognitionRepository.findById(sessionId, recognitionId)
                    .orElseThrow(() -> new ApiException(ErrorCode.RECOGNITION_NOT_FOUND));
            if (!question.questionId().equals(recognition.questionId())) {
                throw new ApiException(ErrorCode.INVALID_REQUEST, "다른 질문의 인식 결과입니다");
            }
            if (!recognition.accepted()) {
                throw new ApiException(ErrorCode.INVALID_REQUEST, "확인되지 않은 인식 결과는 사용할 수 없습니다");
            }
            labels.add(recognition.label());
        }
        return List.copyOf(labels);
    }

    // ---- confirm ------------------------------------------------------------------------------

    public ConfirmResult confirm(String sessionId, ConfirmRequest request) {
        Session session = sessionService.requireActive(sessionId);
        if (request.hasAnswerId()) {
            return confirmDraft(sessionId, session, request);
        }
        return new ConfirmResult(confirmLabels(sessionId, session, request.labelsOrEmpty(), request.answer()), true);
    }

    private ConfirmResult confirmDraft(String sessionId, Session session, ConfirmRequest request) {
        AnswerDraft draft = draftRepository.findById(sessionId, request.answerId())
                .orElseThrow(() -> new ApiException(ErrorCode.DRAFT_NOT_FOUND));

        if (draft.status() == DraftStatus.INVALIDATED) {
            throw new ApiException(ErrorCode.DRAFT_INVALIDATED);
        }
        if (draft.status() == DraftStatus.CONFIRMED) {
            // Idempotent replay: the same draft confirms to the same conversation, never a second one.
            Optional<Conversation> already = conversationRepository.findById(sessionId, draft.answerId());
            if (already.isPresent()) {
                return new ConfirmResult(already.get(), false);
            }
        }
        if (request.version() != null && request.version() != draft.version()) {
            throw new ApiException(ErrorCode.VERSION_CONFLICT,
                    "답변 초안 버전이 일치하지 않습니다. 현재 버전: " + draft.version());
        }

        PendingQuestion question = session.currentQuestion();
        if (question == null
                || !draft.questionId().equals(question.questionId())
                || draft.questionVersion() != question.version()) {
            draftRepository.save(sessionId, draft.withStatus(DraftStatus.INVALIDATED, Instant.now()));
            throw new ApiException(ErrorCode.DRAFT_INVALIDATED, "질문이 변경되어 초안이 무효화되었습니다");
        }

        String answer = request.answer() != null && !request.answer().isBlank()
                ? request.answer()
                : draft.answer();
        if (answer == null || answer.isBlank()) {
            throw new ApiException(ErrorCode.INVALID_REQUEST, "확정할 답변 문장이 없습니다.");
        }

        Conversation conversation = Conversation.confirmed(
                draft.answerId(),
                question.questionId(),
                question.text(),
                question.intents(),
                draft.labels(),
                answer.trim(),
                Instant.now(),
                draft.questionVersion());

        conversationRepository.append(sessionId, conversation);
        draftRepository.save(sessionId, draft.withStatus(DraftStatus.CONFIRMED, Instant.now()));
        invalidateOpenDrafts(sessionId, draft.questionId(), draft.answerId());
        sessionRepository.updateCurrentQuestion(sessionId, null);
        eventPublisher.publish(SessionEvent.of(EventType.ANSWER_CONFIRMED, sessionId, conversation));

        return new ConfirmResult(conversation, true);
    }

    /** Legacy path: labels (and/or a typed sentence) with no draft behind them. */
    private Conversation confirmLabels(String sessionId, Session session, List<String> labels, String answerText) {
        PendingQuestion question = requireCurrentQuestion(session);
        validateLabels(labels);

        String answer = answerText;
        if (answer == null || answer.isBlank()) {
            if (labels.isEmpty()) {
                throw new ApiException(ErrorCode.INVALID_REQUEST, "labels 또는 answer 중 하나는 필수입니다.");
            }
            List<Conversation> prior = conversationRepository.findAll(sessionId);
            answer = llmClient.composeAnswer(question.text(), labels, prior);
        }

        Conversation conversation = Conversation.confirmed(
                IdGenerator.uuid(),
                question.questionId(),
                question.text(),
                question.intents(),
                labels,
                answer.trim(),
                Instant.now(),
                question.version());

        conversationRepository.append(sessionId, conversation);
        // The question is cleared below, so any open draft for it can never be confirmed any more.
        invalidateOpenDrafts(sessionId, question.questionId(), null);
        sessionRepository.updateCurrentQuestion(sessionId, null);
        eventPublisher.publish(SessionEvent.of(EventType.ANSWER_CONFIRMED, sessionId, conversation));

        return conversation;
    }

    /** Marks every still-open draft of the question INVALIDATED, except {@code keepAnswerId}. */
    private void invalidateOpenDrafts(String sessionId, String questionId, String keepAnswerId) {
        Instant now = Instant.now();
        for (AnswerDraft draft : draftRepository.findByQuestion(sessionId, questionId)) {
            if (draft.isDraft() && !draft.answerId().equals(keepAnswerId)) {
                draftRepository.save(sessionId, draft.withStatus(DraftStatus.INVALIDATED, now));
            }
        }
    }

    // ---- edit ---------------------------------------------------------------------------------

    /**
     * Edits a confirmed answer. The patient owns their own words: a DOCTOR edit is only recorded as a
     * {@link PendingEdit} proposal and leaves the confirmed sentence in place until the patient sends
     * the same PATCH, which applies it and bumps the version.
     */
    public Conversation updateAnswer(String sessionId, String answerId, String newAnswer, Integer expectedVersion,
                                      Role role) {
        sessionService.requireActive(sessionId);

        Conversation existing = conversationRepository.findById(sessionId, answerId)
                .orElseThrow(() -> new ApiException(ErrorCode.ANSWER_NOT_FOUND));

        if (expectedVersion != null && expectedVersion != existing.version()) {
            throw new ApiException(ErrorCode.VERSION_CONFLICT,
                    "답변 버전이 일치하지 않습니다. 현재 버전: " + existing.version());
        }

        String answer = newAnswer.trim();
        Instant now = Instant.now();
        Conversation updated;
        EventType eventType;
        if (role == Role.DOCTOR) {
            updated = existing.withPendingEdit(new PendingEdit(answer, Role.DOCTOR, now));
            eventType = EventType.ANSWER_EDIT_PROPOSED;
        } else {
            updated = existing.applyEdit(answer, Role.PATIENT, now);
            eventType = EventType.ANSWER_UPDATED;
        }

        conversationRepository.update(sessionId, updated)
                .orElseThrow(() -> new ApiException(ErrorCode.ANSWER_NOT_FOUND));

        eventPublisher.publish(SessionEvent.of(eventType, sessionId, updated));

        return updated;
    }

    public TtsAudio synthesize(String sessionId, String answerId) {
        sessionService.requireActive(sessionId);

        Conversation conversation = conversationRepository.findById(sessionId, answerId)
                .orElseThrow(() -> new ApiException(ErrorCode.ANSWER_NOT_FOUND));

        return ttsClient.synthesize(conversation.answer());
    }

    // ---- helpers ------------------------------------------------------------------------------

    private PendingQuestion requireCurrentQuestion(Session session) {
        PendingQuestion question = session.currentQuestion();
        if (question == null) {
            throw new ApiException(ErrorCode.NO_PENDING_QUESTION);
        }
        return question;
    }

    private void requireQuestionMatches(PendingQuestion question, String questionId, Integer questionVersion) {
        if (questionId != null && !questionId.equals(question.questionId())) {
            throw new ApiException(ErrorCode.QUESTION_NOT_FOUND);
        }
        if (questionVersion != null && questionVersion != question.version()) {
            throw new ApiException(ErrorCode.VERSION_CONFLICT,
                    "질문 버전이 일치하지 않습니다. 현재 버전: " + question.version());
        }
    }

    private void validateLabels(List<String> labels) {
        List<String> allLabels = Intent.allLabels();
        for (String label : labels) {
            if (!allLabels.contains(label)) {
                throw new ApiException(ErrorCode.INVALID_REQUEST, "허용되지 않는 라벨입니다: " + label);
            }
        }
    }
}

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
import com.talkdoc.backend.common.IdGenerator;
import com.talkdoc.backend.question.dto.QuestionResponse;
import com.talkdoc.backend.realtime.EventType;
import com.talkdoc.backend.realtime.SessionEvent;
import com.talkdoc.backend.realtime.SessionEventPublisher;
import com.talkdoc.backend.session.Session;
import com.talkdoc.backend.session.SessionRepository;
import com.talkdoc.backend.session.SessionService;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.Instant;
import java.util.List;

/**
 * Doctor question intake: either the "text" form field is used directly, or "audio" is
 * transcribed via {@link SttClient}. The resulting text is classified into intents by
 * {@link LlmClient}, stored as the session's pending question, and broadcast to listeners.
 *
 * <p>A posted question can be edited in place ({@link #updateQuestion}); the question id is kept and
 * only its version moves, which invalidates any answer draft the patient had already composed.</p>
 */
@Service
public class QuestionService {

    private final SessionService sessionService;
    private final SessionRepository sessionRepository;
    private final ConversationRepository conversationRepository;
    private final AnswerDraftRepository draftRepository;
    private final SttClient sttClient;
    private final LlmClient llmClient;
    private final AnswerModeResolver answerModeResolver;
    private final SessionEventPublisher eventPublisher;

    public QuestionService(SessionService sessionService,
                            SessionRepository sessionRepository,
                            ConversationRepository conversationRepository,
                            AnswerDraftRepository draftRepository,
                            SttClient sttClient,
                            LlmClient llmClient,
                            AnswerModeResolver answerModeResolver,
                            SessionEventPublisher eventPublisher) {
        this.sessionService = sessionService;
        this.sessionRepository = sessionRepository;
        this.conversationRepository = conversationRepository;
        this.draftRepository = draftRepository;
        this.sttClient = sttClient;
        this.llmClient = llmClient;
        this.answerModeResolver = answerModeResolver;
        this.eventPublisher = eventPublisher;
    }

    public QuestionResponse postQuestion(String sessionId, MultipartFile audio, String text) {
        sessionService.requireActive(sessionId);

        String questionText = resolveQuestionText(audio, text);

        PendingQuestion question = new PendingQuestion(
                IdGenerator.uuid(), questionText, null, null, null, null, Instant.now(), 1, null);
        question = withAnalysis(question, questionText);

        sessionRepository.updateCurrentQuestion(sessionId, question);
        eventPublisher.publish(SessionEvent.of(EventType.QUESTION_POSTED, sessionId, question));

        return QuestionResponse.of(question);
    }

    /**
     * Rewrites the pending question in place: same question id, version + 1, intents re-analysed.
     * Drafts the patient composed for an older version are invalidated (kept, but no longer
     * confirmable); recognitions are left untouched so nothing the patient signed is lost.
     *
     * @param expectedVersion the version the doctor last saw; must match the stored one
     */
    public QuestionResponse updateQuestion(String sessionId, String questionId, String text, int expectedVersion) {
        Session session = sessionService.requireActive(sessionId);

        PendingQuestion current = session.currentQuestion();
        if (current == null || !current.questionId().equals(questionId)) {
            throw missingQuestion(sessionId, questionId);
        }
        if (expectedVersion != current.version()) {
            throw new ApiException(ErrorCode.VERSION_CONFLICT,
                    "질문 버전이 일치하지 않습니다. 현재 버전: " + current.version());
        }

        PendingQuestion updated = withAnalysis(new PendingQuestion(
                current.questionId(), text, null, null, null, null,
                current.askedAt(), current.version() + 1, Instant.now()), text);

        sessionRepository.updateCurrentQuestion(sessionId, updated);
        invalidateStaleDrafts(sessionId, updated);
        eventPublisher.publish(SessionEvent.of(EventType.QUESTION_UPDATED, sessionId, updated));

        return QuestionResponse.of(updated);
    }

    /** 404 normally, but 409 when the question is gone because it already has a confirmed answer. */
    private ApiException missingQuestion(String sessionId, String questionId) {
        boolean answered = conversationRepository.findAll(sessionId).stream()
                .map(Conversation::questionId)
                .anyMatch(questionId::equals);
        return new ApiException(answered ? ErrorCode.QUESTION_ALREADY_ANSWERED : ErrorCode.QUESTION_NOT_FOUND);
    }

    private void invalidateStaleDrafts(String sessionId, PendingQuestion updated) {
        Instant now = Instant.now();
        for (AnswerDraft draft : draftRepository.findByQuestion(sessionId, updated.questionId())) {
            if (draft.isDraft() && draft.questionVersion() < updated.version()) {
                draftRepository.save(sessionId, draft.withStatus(DraftStatus.INVALIDATED, now));
            }
        }
    }

    /**
     * Fills in intents/candidates and the answer mode (sign vs. card options) for the question's text,
     * falling back to OTHER. Used both when posting and when editing a question.
     */
    private PendingQuestion withAnalysis(PendingQuestion question, String questionText) {
        IntentAnalysis analysis = llmClient.analyzeIntent(questionText);
        List<Intent> intents = analysis.intents();
        if (intents == null || intents.isEmpty()) {
            intents = List.of(Intent.OTHER);
        }
        Intent primaryIntent = intents.get(0);
        AnswerMode answerMode = answerModeResolver.resolve(primaryIntent, questionText);
        List<String> cardOptions = answerModeResolver.cardOptions(primaryIntent, questionText);
        return new PendingQuestion(question.questionId(), questionText, intents, Intent.candidatesFor(intents),
                answerMode, cardOptions, question.askedAt(), question.version(), question.updatedAt());
    }

    private String resolveQuestionText(MultipartFile audio, String text) {
        if (text != null && !text.isBlank()) {
            return text;
        }
        if (audio == null || audio.isEmpty()) {
            throw new ApiException(ErrorCode.INVALID_REQUEST, "text 또는 audio 중 하나는 필수입니다.");
        }
        String contentType = audio.getContentType();
        if (contentType == null || !(contentType.startsWith("audio/") || contentType.equals("video/webm"))) {
            throw new ApiException(ErrorCode.UNSUPPORTED_MEDIA);
        }

        byte[] bytes;
        try {
            bytes = audio.getBytes();
        } catch (IOException e) {
            throw new ApiException(ErrorCode.INVALID_REQUEST, "오디오 파일을 읽을 수 없습니다.", e);
        }

        String transcribed = sttClient.transcribe(bytes, contentType);
        if (transcribed == null || transcribed.isBlank()) {
            throw new ApiException(ErrorCode.STT_FAILED, "음성을 인식하지 못했습니다.");
        }
        return transcribed;
    }
}

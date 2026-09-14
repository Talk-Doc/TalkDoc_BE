package com.talkdoc.backend.question;

import com.talkdoc.backend.ai.LlmClient;
import com.talkdoc.backend.ai.SttClient;
import com.talkdoc.backend.ai.model.IntentAnalysis;
import com.talkdoc.backend.common.ApiException;
import com.talkdoc.backend.common.ErrorCode;
import com.talkdoc.backend.common.IdGenerator;
import com.talkdoc.backend.question.dto.QuestionResponse;
import com.talkdoc.backend.realtime.EventType;
import com.talkdoc.backend.realtime.SessionEvent;
import com.talkdoc.backend.realtime.SessionEventPublisher;
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
 */
@Service
public class QuestionService {

    private final SessionService sessionService;
    private final SessionRepository sessionRepository;
    private final SttClient sttClient;
    private final LlmClient llmClient;
    private final AnswerModeResolver answerModeResolver;
    private final SessionEventPublisher eventPublisher;

    public QuestionService(SessionService sessionService,
                            SessionRepository sessionRepository,
                            SttClient sttClient,
                            LlmClient llmClient,
                            AnswerModeResolver answerModeResolver,
                            SessionEventPublisher eventPublisher) {
        this.sessionService = sessionService;
        this.sessionRepository = sessionRepository;
        this.sttClient = sttClient;
        this.llmClient = llmClient;
        this.answerModeResolver = answerModeResolver;
        this.eventPublisher = eventPublisher;
    }

    public QuestionResponse postQuestion(String sessionId, MultipartFile audio, String text) {
        sessionService.requireActive(sessionId);

        String questionText = resolveQuestionText(audio, text);

        IntentAnalysis analysis = llmClient.analyzeIntent(questionText);
        List<Intent> intents = analysis.intents();
        if (intents == null || intents.isEmpty()) {
            intents = List.of(Intent.OTHER);
        }
        List<String> candidates = Intent.candidatesFor(intents);
        Intent primaryIntent = intents.get(0);
        AnswerMode answerMode = answerModeResolver.resolve(primaryIntent, questionText);
        List<String> cardOptions = answerModeResolver.cardOptions(primaryIntent, questionText);

        PendingQuestion question = new PendingQuestion(
                IdGenerator.uuid(), questionText, intents, candidates, answerMode, cardOptions, Instant.now());

        sessionRepository.updateCurrentQuestion(sessionId, question);
        eventPublisher.publish(SessionEvent.of(EventType.QUESTION_POSTED, sessionId, question));

        return QuestionResponse.of(question);
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

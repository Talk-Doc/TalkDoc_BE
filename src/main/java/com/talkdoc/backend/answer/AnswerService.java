package com.talkdoc.backend.answer;

import com.talkdoc.backend.ai.LlmClient;
import com.talkdoc.backend.ai.TtsClient;
import com.talkdoc.backend.ai.model.TtsAudio;
import com.talkdoc.backend.answer.dto.PreviewResponse;
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
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;

/**
 * Turns recognised sign labels into a confirmed answer: preview (not stored), confirm (appended
 * to the conversation and clears the pending question), edit an already-confirmed answer, and
 * text-to-speech playback for the doctor.
 */
@Service
public class AnswerService {

    private final SessionService sessionService;
    private final SessionRepository sessionRepository;
    private final ConversationRepository conversationRepository;
    private final LlmClient llmClient;
    private final TtsClient ttsClient;
    private final SessionEventPublisher eventPublisher;

    public AnswerService(SessionService sessionService,
                          SessionRepository sessionRepository,
                          ConversationRepository conversationRepository,
                          LlmClient llmClient,
                          TtsClient ttsClient,
                          SessionEventPublisher eventPublisher) {
        this.sessionService = sessionService;
        this.sessionRepository = sessionRepository;
        this.conversationRepository = conversationRepository;
        this.llmClient = llmClient;
        this.ttsClient = ttsClient;
        this.eventPublisher = eventPublisher;
    }

    public PreviewResponse preview(String sessionId, List<String> labels) {
        Session session = sessionService.requireActive(sessionId);
        PendingQuestion question = requireCurrentQuestion(session);
        validateLabels(labels);

        List<Conversation> prior = conversationRepository.findAll(sessionId);
        String answer = llmClient.composeAnswer(question.text(), labels, prior);

        return new PreviewResponse(question.questionId(), labels, answer);
    }

    public Conversation confirm(String sessionId, List<String> labels, String answerText) {
        Session session = sessionService.requireActive(sessionId);
        PendingQuestion question = requireCurrentQuestion(session);
        validateLabels(labels);

        String answer = answerText;
        if (answer == null || answer.isBlank()) {
            List<Conversation> prior = conversationRepository.findAll(sessionId);
            answer = llmClient.composeAnswer(question.text(), labels, prior);
        }

        Conversation conversation = new Conversation(
                IdGenerator.uuid(),
                question.questionId(),
                question.text(),
                question.intents(),
                labels,
                answer.trim(),
                Instant.now());

        conversationRepository.append(sessionId, conversation);
        sessionRepository.updateCurrentQuestion(sessionId, null);
        eventPublisher.publish(SessionEvent.of(EventType.ANSWER_CONFIRMED, sessionId, conversation));

        return conversation;
    }

    public Conversation updateAnswer(String sessionId, String answerId, String newAnswer) {
        sessionService.requireActive(sessionId);

        Conversation existing = conversationRepository.findById(sessionId, answerId)
                .orElseThrow(() -> new ApiException(ErrorCode.ANSWER_NOT_FOUND));

        Conversation updated = existing.withAnswer(newAnswer.trim());
        conversationRepository.update(sessionId, updated)
                .orElseThrow(() -> new ApiException(ErrorCode.ANSWER_NOT_FOUND));

        eventPublisher.publish(SessionEvent.of(EventType.ANSWER_UPDATED, sessionId, updated));

        return updated;
    }

    public TtsAudio synthesize(String sessionId, String answerId) {
        sessionService.requireActive(sessionId);

        Conversation conversation = conversationRepository.findById(sessionId, answerId)
                .orElseThrow(() -> new ApiException(ErrorCode.ANSWER_NOT_FOUND));

        return ttsClient.synthesize(conversation.answer());
    }

    private PendingQuestion requireCurrentQuestion(Session session) {
        PendingQuestion question = session.currentQuestion();
        if (question == null) {
            throw new ApiException(ErrorCode.NO_PENDING_QUESTION);
        }
        return question;
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

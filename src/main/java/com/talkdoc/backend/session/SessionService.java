package com.talkdoc.backend.session;

import com.talkdoc.backend.answer.AnswerDraft;
import com.talkdoc.backend.answer.AnswerDraftRepository;
import com.talkdoc.backend.answer.Conversation;
import com.talkdoc.backend.answer.ConversationRepository;
import com.talkdoc.backend.auth.Role;
import com.talkdoc.backend.common.ApiException;
import com.talkdoc.backend.common.ErrorCode;
import com.talkdoc.backend.common.IdGenerator;
import com.talkdoc.backend.question.PendingQuestion;
import com.talkdoc.backend.realtime.SessionEventPublisher;
import com.talkdoc.backend.session.dto.SessionDetailResponse;
import com.talkdoc.backend.sign.Recognition;
import com.talkdoc.backend.sign.RecognitionRepository;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;

/**
 * Session lifecycle: creation, lookups (with the standard "not found" / "closed" guards used by the
 * other work packages), detail assembly and deletion.
 */
@Service
public class SessionService {

    private final SessionRepository sessionRepository;
    private final ConversationRepository conversationRepository;
    private final AnswerDraftRepository draftRepository;
    private final RecognitionRepository recognitionRepository;
    private final SessionEventPublisher eventPublisher;

    public SessionService(SessionRepository sessionRepository,
                           ConversationRepository conversationRepository,
                           AnswerDraftRepository draftRepository,
                           RecognitionRepository recognitionRepository,
                           SessionEventPublisher eventPublisher) {
        this.sessionRepository = sessionRepository;
        this.conversationRepository = conversationRepository;
        this.draftRepository = draftRepository;
        this.recognitionRepository = recognitionRepository;
        this.eventPublisher = eventPublisher;
    }

    /** Creates a new ACTIVE session with a fresh doctor/patient token pair. */
    public CreatedSession create() {
        String sessionId = IdGenerator.uuid();
        SessionTokens tokens = new SessionTokens(IdGenerator.token(), IdGenerator.token());
        Session session = new Session(sessionId, SessionStatus.ACTIVE, Instant.now(), null);
        sessionRepository.create(session, tokens);
        return new CreatedSession(session, tokens);
    }

    /** @throws ApiException SESSION_NOT_FOUND when the session does not exist (or has expired). */
    public Session getOrThrow(String sessionId) {
        return sessionRepository.findById(sessionId)
                .orElseThrow(() -> new ApiException(ErrorCode.SESSION_NOT_FOUND));
    }

    /** @throws ApiException SESSION_NOT_FOUND, or SESSION_CLOSED when the session is no longer ACTIVE. */
    public Session requireActive(String sessionId) {
        Session session = getOrThrow(sessionId);
        if (!session.isActive()) {
            throw new ApiException(ErrorCode.SESSION_CLOSED);
        }
        return session;
    }

    /**
     * Session snapshot for one role. Drafts and recognitions are the patient's unconfirmed work and are
     * only returned to the patient; the doctor gets null for both.
     */
    public SessionDetailResponse detail(String sessionId, Role role) {
        Session session = getOrThrow(sessionId);
        List<Conversation> conversations = conversationRepository.findAll(sessionId);

        if (role != Role.PATIENT) {
            return SessionDetailResponse.of(session, conversations, null, null);
        }

        PendingQuestion question = session.currentQuestion();
        List<AnswerDraft> drafts = List.of();
        List<Recognition> recognitions = List.of();
        if (question != null) {
            drafts = draftRepository.findByQuestion(sessionId, question.questionId()).stream()
                    .filter(AnswerDraft::isDraft)
                    .toList();
            recognitions = recognitionRepository.findByQuestion(sessionId, question.questionId(), question.version());
        }
        return SessionDetailResponse.of(session, conversations, drafts, recognitions);
    }

    /** Deletes every key for the session and closes any attached WebSocket connections. Idempotent. */
    public void delete(String sessionId) {
        sessionRepository.delete(sessionId);
        eventPublisher.closeSession(sessionId);
    }

    /** A freshly created session together with the two tokens, which are returned only once. */
    public record CreatedSession(Session session, SessionTokens tokens) {
    }
}

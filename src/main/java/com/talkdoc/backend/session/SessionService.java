package com.talkdoc.backend.session;

import com.talkdoc.backend.answer.Conversation;
import com.talkdoc.backend.answer.ConversationRepository;
import com.talkdoc.backend.common.ApiException;
import com.talkdoc.backend.common.ErrorCode;
import com.talkdoc.backend.common.IdGenerator;
import com.talkdoc.backend.realtime.SessionEventPublisher;
import com.talkdoc.backend.session.dto.SessionDetailResponse;
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
    private final SessionEventPublisher eventPublisher;

    public SessionService(SessionRepository sessionRepository,
                           ConversationRepository conversationRepository,
                           SessionEventPublisher eventPublisher) {
        this.sessionRepository = sessionRepository;
        this.conversationRepository = conversationRepository;
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

    public SessionDetailResponse detail(String sessionId) {
        Session session = getOrThrow(sessionId);
        List<Conversation> conversations = conversationRepository.findAll(sessionId);
        return SessionDetailResponse.of(session, conversations);
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

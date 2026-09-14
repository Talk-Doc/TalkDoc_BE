package com.talkdoc.backend.session;

import com.talkdoc.backend.auth.TokenResolver;
import com.talkdoc.backend.question.PendingQuestion;

import java.util.Optional;

/**
 * Redis-backed session storage. Implemented in WP1 as RedisSessionRepository.
 * Key layout: see {@link com.talkdoc.backend.common.RedisKeys}. Every write must refresh the TTL
 * (talkdoc.session.ttl) on all keys that belong to the session.
 */
public interface SessionRepository extends TokenResolver {

    /** Persists a new ACTIVE session together with its two role tokens (token:{token} lookups included). */
    void create(Session session, SessionTokens tokens);

    Optional<Session> findById(String sessionId);

    /** Replaces the pending question; pass null to clear it after an answer is confirmed. */
    void updateCurrentQuestion(String sessionId, PendingQuestion question);

    /**
     * Deletes every key for the session: session hash, both token lookups, the conversations list
     * (RedisKeys.conversations), the recognitions list and the drafts hash.
     * Must be a no-op when the session does not exist.
     */
    void delete(String sessionId);
}

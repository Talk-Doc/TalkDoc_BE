package com.talkdoc.backend.answer;

import java.util.List;
import java.util.Optional;

/**
 * Answer drafts per session, stored in the hash at RedisKeys.drafts(sessionId) keyed by answerId.
 * Writes must refresh the key TTL (talkdoc.session.ttl).
 */
public interface AnswerDraftRepository {

    /** Inserts or replaces the draft with this answerId. */
    void save(String sessionId, AnswerDraft draft);

    Optional<AnswerDraft> findById(String sessionId, String answerId);

    /** All drafts of the session, oldest first. */
    List<AnswerDraft> findAll(String sessionId);

    /** All drafts made for one question id (any version), oldest first. */
    List<AnswerDraft> findByQuestion(String sessionId, String questionId);
}

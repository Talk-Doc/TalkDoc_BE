package com.talkdoc.backend.answer;

import java.util.List;
import java.util.Optional;

/**
 * Ordered list of confirmed conversations per session, stored at RedisKeys.conversations(sessionId)
 * as JSON elements (RPUSH). Implemented in WP2 as RedisConversationRepository.
 * Writes must refresh the key TTL (talkdoc.session.ttl).
 */
public interface ConversationRepository {

    void append(String sessionId, Conversation conversation);

    List<Conversation> findAll(String sessionId);

    Optional<Conversation> findById(String sessionId, String answerId);

    /** Replaces the element with the same answerId in place (LSET); returns the stored value or empty if missing. */
    Optional<Conversation> update(String sessionId, Conversation conversation);
}

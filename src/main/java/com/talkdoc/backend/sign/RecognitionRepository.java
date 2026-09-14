package com.talkdoc.backend.sign;

import java.util.List;
import java.util.Optional;

/**
 * Ordered list of sign recognitions per session, stored at RedisKeys.recognitions(sessionId) as JSON
 * elements (RPUSH). Writes must refresh the key TTL (talkdoc.session.ttl).
 */
public interface RecognitionRepository {

    void append(String sessionId, Recognition recognition);

    List<Recognition> findAll(String sessionId);

    Optional<Recognition> findById(String sessionId, String recognitionId);

    /** Recognitions belonging to one question version, in recognition order. */
    List<Recognition> findByQuestion(String sessionId, String questionId, int questionVersion);
}

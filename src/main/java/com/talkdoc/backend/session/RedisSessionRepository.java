package com.talkdoc.backend.session;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.talkdoc.backend.auth.AuthenticatedPrincipal;
import com.talkdoc.backend.auth.Role;
import com.talkdoc.backend.common.RedisKeys;
import com.talkdoc.backend.config.TalkDocProperties;
import com.talkdoc.backend.question.PendingQuestion;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Repository;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Redis-backed implementation of {@link SessionRepository} (and therefore {@link com.talkdoc.backend.auth.TokenResolver}).
 * Uses a plain {@link StringRedisTemplate} and the Spring-managed {@link ObjectMapper} to (de)serialize
 * {@link PendingQuestion} as JSON in the session hash. Every write refreshes the TTL on every key that
 * belongs to the session (talkdoc.session.ttl).
 */
@Repository
public class RedisSessionRepository implements SessionRepository {

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final TalkDocProperties properties;

    public RedisSessionRepository(StringRedisTemplate redisTemplate, ObjectMapper objectMapper, TalkDocProperties properties) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    @Override
    public void create(Session session, SessionTokens tokens) {
        String sessionKey = RedisKeys.session(session.sessionId());
        String doctorTokenKey = RedisKeys.token(tokens.doctorToken());
        String patientTokenKey = RedisKeys.token(tokens.patientToken());

        Map<String, String> fields = new HashMap<>();
        fields.put(RedisKeys.SESSION_FIELD_CREATED_AT, session.createdAt().toString());
        fields.put(RedisKeys.SESSION_FIELD_STATUS, session.status().name());
        fields.put(RedisKeys.SESSION_FIELD_DOCTOR_TOKEN, tokens.doctorToken());
        fields.put(RedisKeys.SESSION_FIELD_PATIENT_TOKEN, tokens.patientToken());
        redisTemplate.opsForHash().putAll(sessionKey, fields);

        redisTemplate.opsForValue().set(doctorTokenKey, session.sessionId() + ":" + Role.DOCTOR.name());
        redisTemplate.opsForValue().set(patientTokenKey, session.sessionId() + ":" + Role.PATIENT.name());

        Duration ttl = properties.session().ttl();
        redisTemplate.expire(sessionKey, ttl);
        redisTemplate.expire(doctorTokenKey, ttl);
        redisTemplate.expire(patientTokenKey, ttl);
    }

    @Override
    public Optional<Session> findById(String sessionId) {
        String sessionKey = RedisKeys.session(sessionId);
        Map<Object, Object> raw = redisTemplate.opsForHash().entries(sessionKey);
        if (raw.isEmpty()) {
            return Optional.empty();
        }

        String status = asString(raw.get(RedisKeys.SESSION_FIELD_STATUS));
        String createdAt = asString(raw.get(RedisKeys.SESSION_FIELD_CREATED_AT));
        String currentQuestionJson = asString(raw.get(RedisKeys.SESSION_FIELD_CURRENT_QUESTION));

        PendingQuestion currentQuestion = null;
        if (currentQuestionJson != null && !currentQuestionJson.isBlank()) {
            currentQuestion = readPendingQuestion(sessionId, currentQuestionJson);
        }

        Session session = new Session(sessionId, SessionStatus.valueOf(status), Instant.parse(createdAt), currentQuestion);
        return Optional.of(session);
    }

    @Override
    public void updateCurrentQuestion(String sessionId, PendingQuestion question) {
        String sessionKey = RedisKeys.session(sessionId);
        if (question == null) {
            redisTemplate.opsForHash().delete(sessionKey, RedisKeys.SESSION_FIELD_CURRENT_QUESTION);
        } else {
            redisTemplate.opsForHash().put(sessionKey, RedisKeys.SESSION_FIELD_CURRENT_QUESTION, writePendingQuestion(sessionId, question));
        }
        refreshTtl(sessionId);
    }

    @Override
    public void delete(String sessionId) {
        String sessionKey = RedisKeys.session(sessionId);
        if (!Boolean.TRUE.equals(redisTemplate.hasKey(sessionKey))) {
            return;
        }

        List<Object> hashKeys = List.of(RedisKeys.SESSION_FIELD_DOCTOR_TOKEN, RedisKeys.SESSION_FIELD_PATIENT_TOKEN);
        List<Object> values = redisTemplate.opsForHash().multiGet(sessionKey, hashKeys);

        Set<String> keysToDelete = new LinkedHashSet<>();
        keysToDelete.add(sessionKey);
        keysToDelete.add(RedisKeys.conversations(sessionId));
        if (values.get(0) instanceof String doctorToken) {
            keysToDelete.add(RedisKeys.token(doctorToken));
        }
        if (values.get(1) instanceof String patientToken) {
            keysToDelete.add(RedisKeys.token(patientToken));
        }

        redisTemplate.delete(keysToDelete);
    }

    @Override
    public Optional<AuthenticatedPrincipal> resolve(String token) {
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }
        String value = redisTemplate.opsForValue().get(RedisKeys.token(token));
        if (value == null) {
            return Optional.empty();
        }
        Optional<AuthenticatedPrincipal> principal = parsePrincipal(token, value);
        if (principal.isEmpty()) {
            return Optional.empty();
        }
        // A token must not resolve for a session that has since been deleted.
        if (!Boolean.TRUE.equals(redisTemplate.hasKey(RedisKeys.session(principal.get().sessionId())))) {
            return Optional.empty();
        }
        return principal;
    }

    /**
     * Parses a "{sessionId}:{ROLE}" value stored at token:{token}. Package-private and static for direct
     * unit testing without a Redis connection.
     */
    static Optional<AuthenticatedPrincipal> parsePrincipal(String token, String value) {
        if (value == null) {
            return Optional.empty();
        }
        int separatorIndex = value.lastIndexOf(':');
        if (separatorIndex <= 0 || separatorIndex == value.length() - 1) {
            return Optional.empty();
        }
        String sessionId = value.substring(0, separatorIndex);
        String roleName = value.substring(separatorIndex + 1);
        Role role;
        try {
            role = Role.valueOf(roleName);
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
        return Optional.of(new AuthenticatedPrincipal(sessionId, role, token));
    }

    private void refreshTtl(String sessionId) {
        Duration ttl = properties.session().ttl();
        String sessionKey = RedisKeys.session(sessionId);

        List<Object> hashKeys = List.of(RedisKeys.SESSION_FIELD_DOCTOR_TOKEN, RedisKeys.SESSION_FIELD_PATIENT_TOKEN);
        List<Object> values = redisTemplate.opsForHash().multiGet(sessionKey, hashKeys);

        redisTemplate.expire(sessionKey, ttl);
        if (values.get(0) instanceof String doctorToken) {
            redisTemplate.expire(RedisKeys.token(doctorToken), ttl);
        }
        if (values.get(1) instanceof String patientToken) {
            redisTemplate.expire(RedisKeys.token(patientToken), ttl);
        }

        String conversationsKey = RedisKeys.conversations(sessionId);
        if (Boolean.TRUE.equals(redisTemplate.hasKey(conversationsKey))) {
            redisTemplate.expire(conversationsKey, ttl);
        }
    }

    private PendingQuestion readPendingQuestion(String sessionId, String json) {
        try {
            return objectMapper.readValue(json, PendingQuestion.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to parse current_question for session " + sessionId, e);
        }
    }

    private String writePendingQuestion(String sessionId, PendingQuestion question) {
        try {
            return objectMapper.writeValueAsString(question);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize current_question for session " + sessionId, e);
        }
    }

    private static String asString(Object value) {
        return value == null ? null : value.toString();
    }
}

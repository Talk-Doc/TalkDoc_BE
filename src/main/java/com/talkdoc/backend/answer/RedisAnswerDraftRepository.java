package com.talkdoc.backend.answer;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.talkdoc.backend.common.ApiException;
import com.talkdoc.backend.common.ErrorCode;
import com.talkdoc.backend.common.RedisKeys;
import com.talkdoc.backend.config.TalkDocProperties;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Repository;

import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Redis-backed {@link AnswerDraftRepository}: each {@link AnswerDraft} is a JSON hash value at
 * {@link RedisKeys#drafts(String)} keyed by its answerId. Hashes are unordered, so reads sort by
 * {@code createdAt}. Every write refreshes the key TTL.
 */
@Repository
public class RedisAnswerDraftRepository implements AnswerDraftRepository {

    private static final Comparator<AnswerDraft> BY_CREATED_AT =
            Comparator.comparing(AnswerDraft::createdAt, Comparator.nullsFirst(Comparator.<Instant>naturalOrder()))
                    .thenComparing(AnswerDraft::answerId, Comparator.nullsFirst(Comparator.naturalOrder()));

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final TalkDocProperties properties;

    public RedisAnswerDraftRepository(StringRedisTemplate redisTemplate,
                                       ObjectMapper objectMapper,
                                       TalkDocProperties properties) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    @Override
    public void save(String sessionId, AnswerDraft draft) {
        String key = RedisKeys.drafts(sessionId);
        redisTemplate.opsForHash().put(key, draft.answerId(), write(draft));
        redisTemplate.expire(key, ttl());
    }

    @Override
    public Optional<AnswerDraft> findById(String sessionId, String answerId) {
        if (answerId == null) {
            return Optional.empty();
        }
        Object raw = redisTemplate.opsForHash().get(RedisKeys.drafts(sessionId), answerId);
        return raw == null ? Optional.empty() : Optional.of(read(raw.toString()));
    }

    @Override
    public List<AnswerDraft> findAll(String sessionId) {
        Map<Object, Object> raw = redisTemplate.opsForHash().entries(RedisKeys.drafts(sessionId));
        if (raw.isEmpty()) {
            return List.of();
        }
        return raw.values().stream()
                .map(v -> read(v.toString()))
                .sorted(BY_CREATED_AT)
                .toList();
    }

    @Override
    public List<AnswerDraft> findByQuestion(String sessionId, String questionId) {
        if (questionId == null) {
            return List.of();
        }
        return findAll(sessionId).stream()
                .filter(d -> questionId.equals(d.questionId()))
                .toList();
    }

    private Duration ttl() {
        return properties.session().ttl();
    }

    private String write(AnswerDraft draft) {
        try {
            return objectMapper.writeValueAsString(draft);
        } catch (JsonProcessingException e) {
            throw new ApiException(ErrorCode.INTERNAL_ERROR, "답변 초안을 저장할 수 없습니다.", e);
        }
    }

    private AnswerDraft read(String json) {
        try {
            return objectMapper.readValue(json, AnswerDraft.class);
        } catch (JsonProcessingException e) {
            throw new ApiException(ErrorCode.INTERNAL_ERROR, "답변 초안을 읽을 수 없습니다.", e);
        }
    }
}

package com.talkdoc.backend.answer;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.talkdoc.backend.common.ApiException;
import com.talkdoc.backend.common.ErrorCode;
import com.talkdoc.backend.common.RedisKeys;
import com.talkdoc.backend.config.TalkDocProperties;
import org.springframework.data.redis.core.ListOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Repository;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

/**
 * Redis-backed implementation of {@link ConversationRepository}. Stores each {@link Conversation}
 * as a JSON element of the list at {@link RedisKeys#conversations(String)} (RPUSH, ordered).
 * Every write refreshes the key's TTL (talkdoc.session.ttl).
 */
@Repository
public class RedisConversationRepository implements ConversationRepository {

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final TalkDocProperties properties;

    public RedisConversationRepository(StringRedisTemplate redisTemplate, ObjectMapper objectMapper, TalkDocProperties properties) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    @Override
    public void append(String sessionId, Conversation conversation) {
        String key = RedisKeys.conversations(sessionId);
        redisTemplate.opsForList().rightPush(key, write(conversation));
        redisTemplate.expire(key, ttl());
    }

    @Override
    public List<Conversation> findAll(String sessionId) {
        ListOperations<String, String> listOps = redisTemplate.opsForList();
        List<String> raw = listOps.range(RedisKeys.conversations(sessionId), 0, -1);
        if (raw == null || raw.isEmpty()) {
            return List.of();
        }
        return raw.stream().map(this::read).toList();
    }

    @Override
    public Optional<Conversation> findById(String sessionId, String answerId) {
        return findAll(sessionId).stream()
                .filter(c -> c.answerId().equals(answerId))
                .findFirst();
    }

    @Override
    public Optional<Conversation> update(String sessionId, Conversation conversation) {
        List<Conversation> all = findAll(sessionId);
        int index = -1;
        for (int i = 0; i < all.size(); i++) {
            if (all.get(i).answerId().equals(conversation.answerId())) {
                index = i;
                break;
            }
        }
        if (index < 0) {
            return Optional.empty();
        }
        String key = RedisKeys.conversations(sessionId);
        redisTemplate.opsForList().set(key, index, write(conversation));
        redisTemplate.expire(key, ttl());
        return Optional.of(conversation);
    }

    private Duration ttl() {
        return properties.session().ttl();
    }

    private String write(Conversation conversation) {
        try {
            return objectMapper.writeValueAsString(conversation);
        } catch (JsonProcessingException e) {
            throw new ApiException(ErrorCode.INTERNAL_ERROR, "대화 내용을 저장할 수 없습니다.", e);
        }
    }

    private Conversation read(String json) {
        try {
            return objectMapper.readValue(json, Conversation.class);
        } catch (JsonProcessingException e) {
            throw new ApiException(ErrorCode.INTERNAL_ERROR, "대화 내용을 읽을 수 없습니다.", e);
        }
    }
}

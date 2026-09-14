package com.talkdoc.backend.sign;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.talkdoc.backend.common.ApiException;
import com.talkdoc.backend.common.ErrorCode;
import com.talkdoc.backend.common.RedisKeys;
import com.talkdoc.backend.config.TalkDocProperties;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Repository;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

/**
 * Redis-backed {@link RecognitionRepository}: each {@link Recognition} is a JSON element of the list
 * at {@link RedisKeys#recognitions(String)} (RPUSH, ordered). Every write refreshes the key TTL.
 */
@Repository
public class RedisRecognitionRepository implements RecognitionRepository {

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final TalkDocProperties properties;

    public RedisRecognitionRepository(StringRedisTemplate redisTemplate,
                                       ObjectMapper objectMapper,
                                       TalkDocProperties properties) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    @Override
    public void append(String sessionId, Recognition recognition) {
        String key = RedisKeys.recognitions(sessionId);
        redisTemplate.opsForList().rightPush(key, write(recognition));
        redisTemplate.expire(key, ttl());
    }

    @Override
    public List<Recognition> findAll(String sessionId) {
        List<String> raw = redisTemplate.opsForList().range(RedisKeys.recognitions(sessionId), 0, -1);
        if (raw == null || raw.isEmpty()) {
            return List.of();
        }
        return raw.stream().map(this::read).toList();
    }

    @Override
    public Optional<Recognition> findById(String sessionId, String recognitionId) {
        if (recognitionId == null) {
            return Optional.empty();
        }
        return findAll(sessionId).stream()
                .filter(r -> recognitionId.equals(r.recognitionId()))
                .findFirst();
    }

    @Override
    public List<Recognition> findByQuestion(String sessionId, String questionId, int questionVersion) {
        if (questionId == null) {
            return List.of();
        }
        return findAll(sessionId).stream()
                .filter(r -> questionId.equals(r.questionId()) && r.questionVersion() == questionVersion)
                .toList();
    }

    private Duration ttl() {
        return properties.session().ttl();
    }

    private String write(Recognition recognition) {
        try {
            return objectMapper.writeValueAsString(recognition);
        } catch (JsonProcessingException e) {
            throw new ApiException(ErrorCode.INTERNAL_ERROR, "인식 결과를 저장할 수 없습니다.", e);
        }
    }

    private Recognition read(String json) {
        try {
            return objectMapper.readValue(json, Recognition.class);
        } catch (JsonProcessingException e) {
            throw new ApiException(ErrorCode.INTERNAL_ERROR, "인식 결과를 읽을 수 없습니다.", e);
        }
    }
}

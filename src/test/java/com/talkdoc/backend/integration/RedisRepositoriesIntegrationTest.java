package com.talkdoc.backend.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.talkdoc.backend.answer.AnswerDraft;
import com.talkdoc.backend.answer.AnswerDraftRepository;
import com.talkdoc.backend.answer.Conversation;
import com.talkdoc.backend.answer.ConversationRepository;
import com.talkdoc.backend.answer.DraftStatus;
import com.talkdoc.backend.common.IdGenerator;
import com.talkdoc.backend.common.RedisKeys;
import com.talkdoc.backend.question.AnswerMode;
import com.talkdoc.backend.question.Intent;
import com.talkdoc.backend.question.PendingQuestion;
import com.talkdoc.backend.session.Session;
import com.talkdoc.backend.session.SessionRepository;
import com.talkdoc.backend.session.SessionStatus;
import com.talkdoc.backend.session.SessionTokens;
import com.talkdoc.backend.sign.Recognition;
import com.talkdoc.backend.sign.RecognitionRepository;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Redis round-trips for the recognition and draft repositories, plus the backward-compatibility
 * guarantee that JSON written before question/answer versioning existed still deserializes.
 */
@SpringBootTest
@ActiveProfiles("test")
@EnabledIf(value = "com.talkdoc.backend.integration.RedisTestSupport#isAvailable", disabledReason = "Redis not available")
class RedisRepositoriesIntegrationTest {

    @DynamicPropertySource
    static void redis(DynamicPropertyRegistry registry) {
        RedisTestSupport.register(registry);
    }

    @BeforeAll
    static void requireRedis() {
        org.junit.jupiter.api.Assumptions.assumeTrue(RedisTestSupport.isAvailable());
    }

    @Autowired
    RecognitionRepository recognitionRepository;

    @Autowired
    AnswerDraftRepository draftRepository;

    @Autowired
    ConversationRepository conversationRepository;

    @Autowired
    SessionRepository sessionRepository;

    @Autowired
    StringRedisTemplate redisTemplate;

    @Autowired
    ObjectMapper objectMapper;

    // ---- recognitions -------------------------------------------------------------------------

    @Test
    void recognitions_roundTripInOrderAndFilterByQuestionVersion() {
        String sessionId = newSessionId();
        Recognition first = recognition("r1", "q1", 1, "배", true);
        Recognition second = recognition("r2", "q1", 1, "아프다", true);
        Recognition otherVersion = recognition("r3", "q1", 2, "머리", true);
        Recognition rejected = recognition("r4", "q1", 1, "기침", false);

        recognitionRepository.append(sessionId, first);
        recognitionRepository.append(sessionId, second);
        recognitionRepository.append(sessionId, otherVersion);
        recognitionRepository.append(sessionId, rejected);

        assertThat(recognitionRepository.findAll(sessionId))
                .containsExactly(first, second, otherVersion, rejected);
        assertThat(recognitionRepository.findById(sessionId, "r2")).contains(second);
        assertThat(recognitionRepository.findById(sessionId, "missing")).isEmpty();
        assertThat(recognitionRepository.findByQuestion(sessionId, "q1", 1))
                .containsExactly(first, second, rejected);
        assertThat(recognitionRepository.findByQuestion(sessionId, "q1", 2)).containsExactly(otherVersion);
        assertThat(recognitionRepository.findByQuestion(sessionId, "other", 1)).isEmpty();

        assertThat(redisTemplate.getExpire(RedisKeys.recognitions(sessionId))).isPositive();
    }

    @Test
    void recognitions_ofAnUnknownSessionAreEmpty() {
        assertThat(recognitionRepository.findAll("no-such-session")).isEmpty();
        assertThat(recognitionRepository.findById("no-such-session", "r1")).isEmpty();
    }

    // ---- drafts -------------------------------------------------------------------------------

    @Test
    void drafts_roundTripAndSaveReplacesInPlace() {
        String sessionId = newSessionId();
        AnswerDraft first = draft("d1", "q1", 1, DraftStatus.DRAFT, Instant.parse("2024-01-01T00:00:00Z"));
        AnswerDraft second = draft("d2", "q2", 1, DraftStatus.DRAFT, Instant.parse("2024-01-01T00:01:00Z"));
        draftRepository.save(sessionId, first);
        draftRepository.save(sessionId, second);

        assertThat(draftRepository.findAll(sessionId)).containsExactly(first, second);
        assertThat(draftRepository.findById(sessionId, "d1")).contains(first);
        assertThat(draftRepository.findByQuestion(sessionId, "q1")).containsExactly(first);
        assertThat(draftRepository.findByQuestion(sessionId, "unknown")).isEmpty();

        AnswerDraft invalidated = first.withStatus(DraftStatus.INVALIDATED, Instant.now());
        draftRepository.save(sessionId, invalidated);

        assertThat(draftRepository.findAll(sessionId)).hasSize(2);
        assertThat(draftRepository.findById(sessionId, "d1"))
                .get().extracting(AnswerDraft::status).isEqualTo(DraftStatus.INVALIDATED);
        assertThat(redisTemplate.getExpire(RedisKeys.drafts(sessionId))).isPositive();
    }

    @Test
    void drafts_ofAnUnknownSessionAreEmpty() {
        assertThat(draftRepository.findAll("no-such-session")).isEmpty();
        assertThat(draftRepository.findById("no-such-session", "d1")).isEmpty();
    }

    // ---- session deletion ---------------------------------------------------------------------

    @Test
    void deletingASessionRemovesRecognitionsAndDrafts() {
        String sessionId = IdGenerator.uuid();
        SessionTokens tokens = new SessionTokens(IdGenerator.token(), IdGenerator.token());
        sessionRepository.create(new Session(sessionId, SessionStatus.ACTIVE, Instant.now(), null), tokens);

        recognitionRepository.append(sessionId, recognition("r1", "q1", 1, "배", true));
        draftRepository.save(sessionId, draft("d1", "q1", 1, DraftStatus.DRAFT, Instant.now()));
        conversationRepository.append(sessionId, Conversation.confirmed("a1", "q1", "어디가 아프세요?",
                List.of(Intent.BODY_LOCATION), List.of("배"), "배요.", Instant.now(), 1));

        assertThat(redisTemplate.hasKey(RedisKeys.recognitions(sessionId))).isTrue();
        assertThat(redisTemplate.hasKey(RedisKeys.drafts(sessionId))).isTrue();

        sessionRepository.delete(sessionId);

        assertThat(redisTemplate.hasKey(RedisKeys.session(sessionId))).isFalse();
        assertThat(redisTemplate.hasKey(RedisKeys.conversations(sessionId))).isFalse();
        assertThat(redisTemplate.hasKey(RedisKeys.recognitions(sessionId))).isFalse();
        assertThat(redisTemplate.hasKey(RedisKeys.drafts(sessionId))).isFalse();
    }

    @Test
    void updatingTheCurrentQuestionRefreshesTheTtlOfTheNewKeysToo() {
        String sessionId = IdGenerator.uuid();
        SessionTokens tokens = new SessionTokens(IdGenerator.token(), IdGenerator.token());
        sessionRepository.create(new Session(sessionId, SessionStatus.ACTIVE, Instant.now(), null), tokens);
        recognitionRepository.append(sessionId, recognition("r1", "q1", 1, "배", true));
        draftRepository.save(sessionId, draft("d1", "q1", 1, DraftStatus.DRAFT, Instant.now()));
        redisTemplate.expire(RedisKeys.recognitions(sessionId), java.time.Duration.ofSeconds(5));
        redisTemplate.expire(RedisKeys.drafts(sessionId), java.time.Duration.ofSeconds(5));

        sessionRepository.updateCurrentQuestion(sessionId, new PendingQuestion("q1", "어디가 아프세요?",
                List.of(Intent.BODY_LOCATION), List.of("배"), AnswerMode.SIGN_REQUIRED, List.of(), Instant.now(), 1, null));

        assertThat(redisTemplate.getExpire(RedisKeys.recognitions(sessionId))).isGreaterThan(10);
        assertThat(redisTemplate.getExpire(RedisKeys.drafts(sessionId))).isGreaterThan(10);

        sessionRepository.delete(sessionId);
    }

    // ---- backward compatibility ---------------------------------------------------------------

    @Test
    void oldPendingQuestionJsonWithoutVersionStillReadsBackAtVersionOne() {
        String sessionId = IdGenerator.uuid();
        SessionTokens tokens = new SessionTokens(IdGenerator.token(), IdGenerator.token());
        sessionRepository.create(new Session(sessionId, SessionStatus.ACTIVE, Instant.now(), null), tokens);

        String legacyJson = """
                {"question_id":"q-old","text":"어디가 아프세요?","intents":["BODY_LOCATION"],
                 "candidates":["배"],"asked_at":"2024-01-01T00:00:00Z"}""";
        redisTemplate.opsForHash().put(RedisKeys.session(sessionId),
                RedisKeys.SESSION_FIELD_CURRENT_QUESTION, legacyJson);

        Optional<Session> session = sessionRepository.findById(sessionId);

        assertThat(session).isPresent();
        PendingQuestion question = session.get().currentQuestion();
        assertThat(question.questionId()).isEqualTo("q-old");
        assertThat(question.version()).isEqualTo(1);
        assertThat(question.updatedAt()).isNull();

        sessionRepository.delete(sessionId);
    }

    @Test
    void oldConversationJsonWithoutVersionStillReadsBackAtVersionOne() {
        String sessionId = newSessionId();
        String legacyJson = """
                {"answer_id":"a-old","question_id":"q-old","question":"어디가 아프세요?",
                 "intents":["BODY_LOCATION"],"signs":["배","아프다"],"answer":"배가 아파요.",
                 "confirmed_at":"2024-01-01T00:00:00Z"}""";
        redisTemplate.opsForList().rightPush(RedisKeys.conversations(sessionId), legacyJson);

        List<Conversation> conversations = conversationRepository.findAll(sessionId);

        assertThat(conversations).hasSize(1);
        Conversation conversation = conversations.get(0);
        assertThat(conversation.answerId()).isEqualTo("a-old");
        assertThat(conversation.answer()).isEqualTo("배가 아파요.");
        assertThat(conversation.version()).isEqualTo(1);
        assertThat(conversation.questionVersion()).isEqualTo(1);
        assertThat(conversation.editedBy()).isNull();
        assertThat(conversation.pendingEdit()).isNull();
    }

    @Test
    void oldAnswerDraftJsonWithoutVersionStillReadsBackAtVersionOne() throws Exception {
        String sessionId = newSessionId();
        String legacyJson = """
                {"answer_id":"d-old","question_id":"q-old","labels":["배"],"answer":"배요.",
                 "created_at":"2024-01-01T00:00:00Z"}""";
        redisTemplate.opsForHash().put(RedisKeys.drafts(sessionId), "d-old", legacyJson);

        AnswerDraft draft = draftRepository.findById(sessionId, "d-old").orElseThrow();

        assertThat(draft.version()).isEqualTo(1);
        assertThat(draft.questionVersion()).isEqualTo(1);
        assertThat(draft.status()).isEqualTo(DraftStatus.DRAFT);
        // and the round-trip through Jackson is stable
        assertThat(objectMapper.readValue(objectMapper.writeValueAsString(draft), AnswerDraft.class))
                .isEqualTo(draft);
    }

    // ---- helpers ------------------------------------------------------------------------------

    /** A throw-away session id; no session hash is created, only the per-session child keys. */
    private String newSessionId() {
        String sessionId = IdGenerator.uuid();
        redisTemplate.delete(List.of(RedisKeys.recognitions(sessionId), RedisKeys.drafts(sessionId),
                RedisKeys.conversations(sessionId)));
        return sessionId;
    }

    private static Recognition recognition(String id, String questionId, int questionVersion, String label,
                                            boolean accepted) {
        return new Recognition(id, questionId, questionVersion, label, 0.9, accepted,
                accepted ? null : "LOW_CONFIDENCE", "mock", "req-" + id,
                Instant.parse("2024-01-01T00:00:00Z"));
    }

    private static AnswerDraft draft(String answerId, String questionId, int questionVersion,
                                      DraftStatus status, Instant createdAt) {
        return new AnswerDraft(answerId, questionId, questionVersion, List.of("배"), List.of("r1"), "배요.",
                1, status, createdAt, createdAt);
    }
}

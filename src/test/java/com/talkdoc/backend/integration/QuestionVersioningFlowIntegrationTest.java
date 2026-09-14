package com.talkdoc.backend.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.net.URI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The versioned intake flow end to end: question edit (version 1 → 2), stored sign recognitions,
 * a preview draft confirmed by answer_id (idempotently), the doctor's propose / patient's apply
 * answer edit, and the role-dependent session detail.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@EnabledIf(value = "com.talkdoc.backend.integration.RedisTestSupport#isAvailable", disabledReason = "Redis not available")
class QuestionVersioningFlowIntegrationTest {

    @DynamicPropertySource
    static void redis(DynamicPropertyRegistry registry) {
        RedisTestSupport.register(registry);
    }

    @BeforeAll
    static void requireRedis() {
        org.junit.jupiter.api.Assumptions.assumeTrue(RedisTestSupport.isAvailable());
    }

    @LocalServerPort
    int port;

    @Autowired
    ObjectMapper objectMapper;

    RestClient http;

    private RestClient http() {
        if (http == null) {
            http = RestClient.builder()
                    .baseUrl("http://localhost:" + port)
                    .defaultStatusHandler(status -> true, (req, res) -> { })
                    .build();
        }
        return http;
    }

    @Test
    void versionedFlowFromQuestionEditToAnswerEdit() throws Exception {
        JsonNode session = json(http().post().uri("/api/sessions").retrieve().toEntity(String.class));
        String sessionId = session.get("session_id").asText();
        String doctor = session.get("doctor_token").asText();
        String patient = session.get("patient_token").asText();
        EventCollector patientWs = connect(sessionId, patient);

        // 1. question at version 1
        JsonNode question = postQuestion(sessionId, doctor, "어디가 아파서 오셨어요?");
        String questionId = question.get("question_id").asText();
        assertThat(question.get("version").asInt()).isEqualTo(1);
        assertThat(patientWs.next().get("type").asText()).isEqualTo("QUESTION_POSTED");

        // 2. the doctor rewords it: same id, version 2, intents re-analysed, WS event
        ResponseEntity<String> patched = patchQuestion(sessionId, doctor, questionId,
                Map.of("text", "어떤 증상이 있으세요?", "version", 1));
        assertThat(patched.getStatusCode()).as(patched.getBody()).isEqualTo(HttpStatus.OK);
        JsonNode updated = json(patched);
        assertThat(updated.get("question_id").asText()).isEqualTo(questionId);
        assertThat(updated.get("version").asInt()).isEqualTo(2);
        assertThat(updated.get("text").asText()).isEqualTo("어떤 증상이 있으세요?");
        assertThat(updated.get("updated_at").asText()).isNotBlank();
        assertThat(toList(updated.get("intents"))).containsExactly("SYMPTOM");
        JsonNode updateEvent = patientWs.next();
        assertThat(updateEvent.get("type").asText()).isEqualTo("QUESTION_UPDATED");
        assertThat(updateEvent.get("payload").get("version").asInt()).isEqualTo(2);

        // the stale version is refused
        assertThat(patchQuestion(sessionId, doctor, questionId, Map.of("text", "또 바꿔요", "version", 1))
                .getStatusCode()).isEqualTo(HttpStatus.CONFLICT);

        // 3. two sign videos, each stored as a recognition
        JsonNode sign1 = postSign(sessionId, patient, "video/webm");
        JsonNode sign2 = postSign(sessionId, patient, "video/webm;labels=\"아프다\"");
        String r1 = sign1.get("recognition_id").asText();
        String r2 = sign2.get("recognition_id").asText();
        assertThat(r1).isNotBlank().isNotEqualTo(r2);
        assertThat(sign1.get("question_version").asInt()).isEqualTo(2);

        // 4. preview against the current question version, by recognition id
        Map<String, Object> previewBody = new HashMap<>();
        previewBody.put("question_id", questionId);
        previewBody.put("question_version", 2);
        previewBody.put("recognition_ids", List.of(r1, r2));
        ResponseEntity<String> previewRes = post(sessionId, patient, "/answer/preview", previewBody);
        assertThat(previewRes.getStatusCode()).as(previewRes.getBody()).isEqualTo(HttpStatus.OK);
        JsonNode preview = json(previewRes);
        String answerId = preview.get("answer_id").asText();
        assertThat(preview.get("question_version").asInt()).isEqualTo(2);
        assertThat(preview.get("version").asInt()).isEqualTo(1);
        assertThat(toList(preview.get("labels"))).containsExactly("배", "아프다");
        assertThat(toList(preview.get("recognition_ids"))).containsExactly(r1, r2);
        assertThat(preview.get("answer").asText()).isEqualTo("배가 아파요.");

        // the patient's own session view carries the open draft and the recognitions
        JsonNode patientDetail = json(getSession(sessionId, patient));
        assertThat(patientDetail.get("question_version").asInt()).isEqualTo(2);
        assertThat(patientDetail.get("drafts")).hasSize(1);
        assertThat(patientDetail.get("drafts").get(0).get("answer_id").asText()).isEqualTo(answerId);
        assertThat(patientDetail.get("drafts").get(0).get("status").asText()).isEqualTo("DRAFT");
        assertThat(patientDetail.get("recognitions")).hasSize(2);

        // 5. confirm the draft by id → 201
        ResponseEntity<String> confirmRes = post(sessionId, patient, "/answer/confirm",
                Map.of("answer_id", answerId, "version", 1));
        assertThat(confirmRes.getStatusCode()).as(confirmRes.getBody()).isEqualTo(HttpStatus.CREATED);
        JsonNode confirmed = json(confirmRes);
        assertThat(confirmed.get("answer_id").asText()).isEqualTo(answerId);
        assertThat(confirmed.get("question_version").asInt()).isEqualTo(2);
        assertThat(confirmed.get("version").asInt()).isEqualTo(1);
        assertThat(toList(confirmed.get("signs"))).containsExactly("배", "아프다");

        // 6. confirming again is idempotent → 200, same answer, still one conversation
        ResponseEntity<String> replayRes = post(sessionId, patient, "/answer/confirm",
                Map.of("answer_id", answerId, "version", 1));
        assertThat(replayRes.getStatusCode()).as(replayRes.getBody()).isEqualTo(HttpStatus.OK);
        assertThat(json(replayRes).get("answer_id").asText()).isEqualTo(answerId);
        assertThat(json(getSession(sessionId, doctor)).get("conversations")).hasSize(1);

        // 7. doctor PATCH only proposes; the confirmed answer is untouched
        ResponseEntity<String> proposeRes = patchAnswer(sessionId, doctor, answerId,
                Map.of("answer", "배가 심하게 아파요.", "version", 1));
        assertThat(proposeRes.getStatusCode()).as(proposeRes.getBody()).isEqualTo(HttpStatus.OK);
        JsonNode proposed = json(proposeRes);
        assertThat(proposed.get("answer").asText()).isEqualTo("배가 아파요.");
        assertThat(proposed.get("version").asInt()).isEqualTo(1);
        assertThat(proposed.get("pending_edit").get("answer").asText()).isEqualTo("배가 심하게 아파요.");
        assertThat(proposed.get("pending_edit").get("proposed_by").asText()).isEqualTo("DOCTOR");
        // the doctor sees the proposal on the conversation too
        assertThat(json(getSession(sessionId, doctor)).get("conversations").get(0).has("pending_edit")).isTrue();

        // 8. patient PATCH applies it and bumps the version
        ResponseEntity<String> applyRes = patchAnswer(sessionId, patient, answerId,
                Map.of("answer", "배가 심하게 아파요.", "version", 1));
        assertThat(applyRes.getStatusCode()).as(applyRes.getBody()).isEqualTo(HttpStatus.OK);
        JsonNode applied = json(applyRes);
        assertThat(applied.get("answer").asText()).isEqualTo("배가 심하게 아파요.");
        assertThat(applied.get("version").asInt()).isEqualTo(2);
        assertThat(applied.get("edited_by").asText()).isEqualTo("PATIENT");
        assertThat(applied.has("pending_edit")).isFalse();

        // the now-stale version is refused
        assertThat(patchAnswer(sessionId, patient, answerId, Map.of("answer", "또 바꿔요.", "version", 1))
                .getStatusCode()).isEqualTo(HttpStatus.CONFLICT);

        // 9. role split on GET: the doctor never receives drafts/recognitions
        JsonNode doctorDetail = json(getSession(sessionId, doctor));
        assertThat(doctorDetail.has("drafts")).isFalse();
        assertThat(doctorDetail.has("recognitions")).isFalse();
        assertThat(doctorDetail.get("conversations")).hasSize(1);
        JsonNode patientDetailAfter = json(getSession(sessionId, patient));
        assertThat(patientDetailAfter.get("conversations")).hasSize(1);
        assertThat(patientDetailAfter.get("drafts")).isEmpty(); // the question was cleared on confirm

        patientWs.close();
        http().delete().uri("/api/sessions/{id}", sessionId).header("Authorization", "Bearer " + doctor)
                .retrieve().toEntity(String.class);
    }

    @Test
    void aDraftMadeBeforeAQuestionEditCannotBeConfirmed() throws Exception {
        JsonNode session = json(http().post().uri("/api/sessions").retrieve().toEntity(String.class));
        String sessionId = session.get("session_id").asText();
        String doctor = session.get("doctor_token").asText();
        String patient = session.get("patient_token").asText();

        JsonNode question = postQuestion(sessionId, doctor, "어디가 아파서 오셨어요?");
        String questionId = question.get("question_id").asText();

        String answerId = json(post(sessionId, patient, "/answer/preview", Map.of("labels", List.of("배", "아프다"))))
                .get("answer_id").asText();

        // the doctor rewords the question after the patient had already previewed an answer
        assertThat(patchQuestion(sessionId, doctor, questionId, Map.of("text", "어떤 증상이 있으세요?", "version", 1))
                .getStatusCode()).isEqualTo(HttpStatus.OK);

        ResponseEntity<String> confirmRes = post(sessionId, patient, "/answer/confirm",
                Map.of("answer_id", answerId));
        assertThat(confirmRes.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(json(confirmRes).get("code").asText()).isEqualTo("DRAFT_INVALIDATED");

        // nothing was confirmed, and the invalidated draft is no longer offered to the patient
        JsonNode detail = json(getSession(sessionId, patient));
        assertThat(detail.get("conversations")).isEmpty();
        assertThat(detail.get("drafts")).isEmpty();
        assertThat(detail.get("question_version").asInt()).isEqualTo(2);

        // previewing again against the new version works and confirms normally
        JsonNode preview = json(post(sessionId, patient, "/answer/preview",
                Map.of("labels", List.of("배", "아프다"), "question_version", 2)));
        ResponseEntity<String> confirmed = post(sessionId, patient, "/answer/confirm",
                Map.of("answer_id", preview.get("answer_id").asText()));
        assertThat(confirmed.getStatusCode()).as(confirmed.getBody()).isEqualTo(HttpStatus.CREATED);
        assertThat(json(confirmed).get("question_version").asInt()).isEqualTo(2);

        http().delete().uri("/api/sessions/{id}", sessionId).header("Authorization", "Bearer " + doctor)
                .retrieve().toEntity(String.class);
    }

    @Test
    void unacceptedRecognitionsCannotBeUsedForAPreview() throws Exception {
        JsonNode session = json(http().post().uri("/api/sessions").retrieve().toEntity(String.class));
        String sessionId = session.get("session_id").asText();
        String doctor = session.get("doctor_token").asText();
        String patient = session.get("patient_token").asText();
        postQuestion(sessionId, doctor, "어떤 증상이 있으세요?");

        JsonNode rejected = postSign(sessionId, patient, "video/webm;labels=\"머리\";confidence=0.5");
        assertThat(rejected.get("all_accepted").asBoolean()).isFalse();
        String recognitionId = rejected.get("recognition_id").asText();

        ResponseEntity<String> res = post(sessionId, patient, "/answer/preview",
                Map.of("recognition_ids", List.of(recognitionId)));
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(json(res).get("message").asText()).contains("확인되지 않은 인식 결과");

        // an unknown recognition id is a 404
        assertThat(post(sessionId, patient, "/answer/preview", Map.of("recognition_ids", List.of("nope")))
                .getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        // and an empty request is a 400
        assertThat(post(sessionId, patient, "/answer/preview", Map.of("labels", List.of()))
                .getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

        http().delete().uri("/api/sessions/{id}", sessionId).header("Authorization", "Bearer " + doctor)
                .retrieve().toEntity(String.class);
    }

    // ---- helpers ------------------------------------------------------------------------------

    private JsonNode postQuestion(String sessionId, String doctorToken, String text) throws Exception {
        MultiValueMap<String, Object> form = new LinkedMultiValueMap<>();
        form.add("text", text);
        ResponseEntity<String> res = http().post().uri("/api/sessions/{id}/question", sessionId)
                .header("Authorization", "Bearer " + doctorToken)
                .contentType(MediaType.MULTIPART_FORM_DATA).body(form)
                .retrieve().toEntity(String.class);
        assertThat(res.getStatusCode()).as(res.getBody()).isEqualTo(HttpStatus.OK);
        return json(res);
    }

    private ResponseEntity<String> patchQuestion(String sessionId, String doctorToken, String questionId,
                                                  Map<String, Object> body) {
        return http().patch().uri("/api/sessions/{id}/questions/{qid}", sessionId, questionId)
                .header("Authorization", "Bearer " + doctorToken)
                .contentType(MediaType.APPLICATION_JSON).body(body)
                .retrieve().toEntity(String.class);
    }

    private ResponseEntity<String> patchAnswer(String sessionId, String token, String answerId,
                                                Map<String, Object> body) {
        return http().patch().uri("/api/sessions/{id}/answer/{aid}", sessionId, answerId)
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON).body(body)
                .retrieve().toEntity(String.class);
    }

    private ResponseEntity<String> post(String sessionId, String token, String path, Map<String, Object> body) {
        return http().post().uri("/api/sessions/" + sessionId + path)
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON).body(body)
                .retrieve().toEntity(String.class);
    }

    private ResponseEntity<String> getSession(String sessionId, String token) {
        return http().get().uri("/api/sessions/{id}", sessionId)
                .header("Authorization", "Bearer " + token).retrieve().toEntity(String.class);
    }

    private JsonNode postSign(String sessionId, String patientToken, String contentType) throws Exception {
        MultiValueMap<String, Object> form = new LinkedMultiValueMap<>();
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.CONTENT_TYPE, contentType);
        form.add("video", new HttpEntity<>(new NamedBytes(new byte[2048], "sign.webm"), headers));
        ResponseEntity<String> res = http().post().uri("/api/sessions/{id}/sign", sessionId)
                .header("Authorization", "Bearer " + patientToken)
                .contentType(MediaType.MULTIPART_FORM_DATA).body(form)
                .retrieve().toEntity(String.class);
        assertThat(res.getStatusCode()).as(res.getBody()).isEqualTo(HttpStatus.OK);
        return json(res);
    }

    private JsonNode json(ResponseEntity<String> res) throws Exception {
        return objectMapper.readTree(res.getBody());
    }

    private static List<String> toList(JsonNode arr) {
        List<String> out = new ArrayList<>();
        arr.forEach(n -> out.add(n.asText()));
        return out;
    }

    private EventCollector connect(String sessionId, String token) throws Exception {
        EventCollector collector = new EventCollector(objectMapper);
        StandardWebSocketClient client = new StandardWebSocketClient();
        WebSocketSession ws = client.execute(collector, new WebSocketHttpHeaders(),
                URI.create("ws://localhost:" + port + "/ws/sessions/" + sessionId + "?token=" + token))
                .get(5, TimeUnit.SECONDS);
        collector.session = ws;
        return collector;
    }

    static final class EventCollector extends TextWebSocketHandler {
        private final BlockingQueue<JsonNode> events = new LinkedBlockingQueue<>();
        private final ObjectMapper mapper;
        WebSocketSession session;

        EventCollector(ObjectMapper mapper) {
            this.mapper = mapper;
        }

        @Override
        protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
            events.add(mapper.readTree(message.getPayload()));
        }

        JsonNode next() throws InterruptedException {
            JsonNode node = events.poll(5, TimeUnit.SECONDS);
            assertThat(node).as("expected a WebSocket event within 5s").isNotNull();
            return node;
        }

        void close() throws Exception {
            if (session != null && session.isOpen()) session.close(CloseStatus.NORMAL);
        }
    }

    /** Multipart file part with a filename. */
    static final class NamedBytes extends ByteArrayResource {
        private final String filename;

        NamedBytes(byte[] bytes, String filename) {
            super(bytes);
            this.filename = filename;
        }

        @Override
        public String getFilename() {
            return filename;
        }
    }
}

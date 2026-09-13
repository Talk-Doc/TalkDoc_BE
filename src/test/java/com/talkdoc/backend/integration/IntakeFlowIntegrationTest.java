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
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Full MVP flow against a running server with the mock AI adapters:
 * create → question → sign → preview → confirm → get → patch → summary → tts → delete,
 * plus WebSocket event delivery and role/session token guards.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@EnabledIf(value = "com.talkdoc.backend.integration.RedisTestSupport#isAvailable", disabledReason = "Redis not available")
class IntakeFlowIntegrationTest {

    @DynamicPropertySource
    static void redis(DynamicPropertyRegistry registry) {
        RedisTestSupport.register(registry);
    }

    @LocalServerPort
    int port;

    @Autowired
    ObjectMapper objectMapper;

    RestClient http;

    @BeforeAll
    static void requireRedis() {
        org.junit.jupiter.api.Assumptions.assumeTrue(RedisTestSupport.isAvailable());
    }

    private RestClient http() {
        if (http == null) {
            http = RestClient.builder()
                    .baseUrl("http://localhost:" + port)
                    .defaultStatusHandler(status -> true, (req, res) -> { }) // never throw; assert on status
                    .build();
        }
        return http;
    }

    @Test
    void fullIntakeFlowWorksEndToEnd() throws Exception {
        // 1. create session
        ResponseEntity<String> created = http().post().uri("/api/sessions").retrieve().toEntity(String.class);
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        JsonNode session = json(created);
        String sessionId = session.get("session_id").asText();
        String doctor = session.get("doctor_token").asText();
        String patient = session.get("patient_token").asText();
        assertThat(doctor).isNotEqualTo(patient);
        assertThat(session.get("patient_join_path").asText()).contains(patient);

        // WebSocket listeners for both roles
        EventCollector doctorWs = connect(sessionId, doctor);
        EventCollector patientWs = connect(sessionId, patient);
        // the doctor connected first, so it is told when the patient joins
        assertThat(doctorWs.next().get("type").asText()).isEqualTo("PEER_JOINED");
        assertThat(patientWs.next().get("type").asText()).isEqualTo("PEER_JOINED");

        // 2. doctor question via text field (skips STT)
        MultiValueMap<String, Object> qForm = new LinkedMultiValueMap<>();
        qForm.add("text", "어디가 아파서 오셨어요?");
        ResponseEntity<String> qRes = http().post().uri("/api/sessions/{id}/question", sessionId)
                .header("Authorization", "Bearer " + doctor)
                .contentType(MediaType.MULTIPART_FORM_DATA).body(qForm)
                .retrieve().toEntity(String.class);
        assertThat(qRes.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode question = json(qRes);
        assertThat(question.get("text").asText()).isEqualTo("어디가 아파서 오셨어요?");
        assertThat(question.get("intent").asText()).isEqualTo("BODY_LOCATION");
        assertThat(toList(question.get("intents"))).containsExactly("BODY_LOCATION", "SYMPTOM");
        assertThat(toList(question.get("candidates"))).contains("배", "아프다");
        assertThat(question.get("supported").asBoolean()).isTrue();
        assertThat(patientWs.next().get("type").asText()).isEqualTo("QUESTION_POSTED");
        assertThat(doctorWs.next().get("type").asText()).isEqualTo("QUESTION_POSTED");

        // 3. patient sign: TalkDoc-VisionAI returns one word per video, so one call per word.
        //    The default mock prediction is 배 (0.94); the second word uses the ;labels= debug override.
        JsonNode sign = postSign(sessionId, patient, "video/webm", 3.5);
        assertThat(toList(sign.get("accepted_labels"))).containsExactly("배");
        assertThat(sign.get("all_accepted").asBoolean()).isTrue();
        assertThat(sign.get("sign").get("label").asText()).isEqualTo("배");
        assertThat(sign.get("sign").get("accepted").asBoolean()).isTrue();
        assertThat(sign.get("sign").has("reason")).isFalse(); // null 은 직렬화되지 않음
        assertThat(sign.get("signs")).hasSize(1);
        assertThat(sign.get("signs").get(0).get("accepted").asBoolean()).isTrue();
        assertThat(sign.get("model_version").asText()).isEqualTo("mock");
        assertThat(sign.get("request_id").asText()).isNotBlank();
        assertThat(sign.get("question_id").asText()).isEqualTo(question.get("question_id").asText());
        assertThat(toList(sign.get("candidates"))).contains("배", "아프다");

        JsonNode sign2 = postSign(sessionId, patient, "video/webm;labels=\"아프다\"", null);
        assertThat(toList(sign2.get("accepted_labels"))).containsExactly("아프다");
        assertThat(sign2.get("all_accepted").asBoolean()).isTrue();

        // duration 은 0 < d <= 20 이어야 한다
        MultiValueMap<String, Object> badForm = new LinkedMultiValueMap<>();
        badForm.add("video", videoPart(new byte[1024], "video/webm"));
        assertThat(http().post().uri("/api/sessions/{id}/sign?duration=25", sessionId)
                .header("Authorization", "Bearer " + patient)
                .contentType(MediaType.MULTIPART_FORM_DATA).body(badForm)
                .retrieve().toEntity(String.class).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

        // 4. preview
        ResponseEntity<String> pRes = http().post().uri("/api/sessions/{id}/answer/preview", sessionId)
                .header("Authorization", "Bearer " + patient)
                .contentType(MediaType.APPLICATION_JSON).body(Map.of("labels", List.of("배", "아프다")))
                .retrieve().toEntity(String.class);
        assertThat(pRes.getStatusCode()).as(pRes.getBody()).isEqualTo(HttpStatus.OK);
        assertThat(json(pRes).get("answer").asText()).isEqualTo("배가 아파요.");

        // 5. confirm
        ResponseEntity<String> cRes = http().post().uri("/api/sessions/{id}/answer/confirm", sessionId)
                .header("Authorization", "Bearer " + patient)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("labels", List.of("배", "아프다"), "answer", "배가 아파요."))
                .retrieve().toEntity(String.class);
        assertThat(cRes.getStatusCode()).as(cRes.getBody()).isEqualTo(HttpStatus.CREATED);
        JsonNode confirmed = json(cRes);
        String answerId = confirmed.get("answer_id").asText();
        assertThat(confirmed.get("answer").asText()).isEqualTo("배가 아파요.");
        JsonNode confirmedEvent = doctorWs.next();
        assertThat(confirmedEvent.get("type").asText()).isEqualTo("ANSWER_CONFIRMED");
        assertThat(confirmedEvent.get("payload").get("answer").asText()).isEqualTo("배가 아파요.");

        // 6. doctor GET: question cleared, one conversation
        ResponseEntity<String> gRes = http().get().uri("/api/sessions/{id}", sessionId)
                .header("Authorization", "Bearer " + doctor).retrieve().toEntity(String.class);
        assertThat(gRes.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode detail = json(gRes);
        assertThat(detail.get("status").asText()).isEqualTo("ACTIVE");
        assertThat(detail.get("conversations")).hasSize(1);
        assertThat(detail.get("conversations").get(0).get("answer_id").asText()).isEqualTo(answerId);
        assertThat(detail.has("current_question") && !detail.get("current_question").isNull()).isFalse();

        // 7. patch answer
        ResponseEntity<String> uRes = http().patch().uri("/api/sessions/{id}/answer/{aid}", sessionId, answerId)
                .header("Authorization", "Bearer " + doctor)
                .contentType(MediaType.APPLICATION_JSON).body(Map.of("answer", "배가 많이 아파요."))
                .retrieve().toEntity(String.class);
        assertThat(uRes.getStatusCode()).as(uRes.getBody()).isEqualTo(HttpStatus.OK);
        assertThat(json(uRes).get("answer").asText()).isEqualTo("배가 많이 아파요.");
        assertThat(patientWs.next().get("type").asText()).isEqualTo("ANSWER_CONFIRMED");
        assertThat(patientWs.next().get("type").asText()).isEqualTo("ANSWER_UPDATED");
        assertThat(doctorWs.next().get("type").asText()).isEqualTo("ANSWER_UPDATED");

        // 8. summary
        ResponseEntity<String> sumRes = http().post().uri("/api/sessions/{id}/summary", sessionId)
                .header("Authorization", "Bearer " + doctor).retrieve().toEntity(String.class);
        assertThat(sumRes.getStatusCode()).as(sumRes.getBody()).isEqualTo(HttpStatus.OK);
        assertThat(json(sumRes).get("summary").asText()).contains("배가 많이 아파요");
        assertThat(json(sumRes).get("conversation_count").asInt()).isEqualTo(1);

        // 9. tts
        ResponseEntity<byte[]> tts = http().get().uri("/api/sessions/{id}/answer/{aid}/tts", sessionId, answerId)
                .header("Authorization", "Bearer " + doctor).retrieve().toEntity(byte[].class);
        assertThat(tts.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(tts.getHeaders().getContentType().toString()).startsWith("audio/");
        assertThat(tts.getBody()).isNotEmpty();

        // 10. role guards
        assertThat(http().delete().uri("/api/sessions/{id}", sessionId)
                .header("Authorization", "Bearer " + patient).retrieve().toEntity(String.class).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(http().get().uri("/api/sessions/{id}", sessionId)
                .retrieve().toEntity(String.class).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(http().get().uri("/api/sessions/{id}", "other-session")
                .header("Authorization", "Bearer " + doctor).retrieve().toEntity(String.class).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);

        // 11. delete → SESSION_CLOSED pushed, tokens dead, idempotent
        assertThat(http().delete().uri("/api/sessions/{id}", sessionId)
                .header("Authorization", "Bearer " + doctor).retrieve().toEntity(String.class).getStatusCode())
                .isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(doctorWs.next().get("type").asText()).isEqualTo("SESSION_CLOSED");
        assertThat(http().get().uri("/api/sessions/{id}", sessionId)
                .header("Authorization", "Bearer " + doctor).retrieve().toEntity(String.class).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(http().delete().uri("/api/sessions/{id}", sessionId)
                .header("Authorization", "Bearer " + doctor).retrieve().toEntity(String.class).getStatusCode())
                .isIn(HttpStatus.NO_CONTENT, HttpStatus.UNAUTHORIZED);
        doctorWs.close();
        patientWs.close();
    }

    @Test
    void lowConfidenceSignsAreNotAccepted() throws Exception {
        JsonNode session = json(http().post().uri("/api/sessions").retrieve().toEntity(String.class));
        String sessionId = session.get("session_id").asText();
        String doctor = session.get("doctor_token").asText();
        String patient = session.get("patient_token").asText();

        MultiValueMap<String, Object> qForm = new LinkedMultiValueMap<>();
        qForm.add("text", "어떤 증상이 있으세요?");
        http().post().uri("/api/sessions/{id}/question", sessionId)
                .header("Authorization", "Bearer " + doctor)
                .contentType(MediaType.MULTIPART_FORM_DATA).body(qForm).retrieve().toEntity(String.class);

        // mock override: confidence below talkdoc.sign.confidence-threshold (0.75) must not be accepted.
        // The label itself is kept even though it is outside the SYMPTOM candidates: candidates never
        // force-limit the prediction (TalkDoc-VisionAI contract).
        JsonNode sign = postSign(sessionId, patient, "video/webm;labels=\"머리,기침\";confidence=0.5", null);
        assertThat(sign.get("all_accepted").asBoolean()).isFalse();
        assertThat(toList(sign.get("accepted_labels"))).isEmpty();
        assertThat(sign.get("sign").get("label").asText()).isEqualTo("머리");
        assertThat(sign.get("sign").get("reason").asText()).isEqualTo("LOW_CONFIDENCE");
        assertThat(sign.get("signs")).hasSize(1);

        // hands not visible → no label at all, signs empty, reason tells the frontend to re-record
        JsonNode noHands = postSign(sessionId, patient, "video/webm;reason=INSUFFICIENT_LANDMARKS", null);
        assertThat(noHands.get("all_accepted").asBoolean()).isFalse();
        assertThat(noHands.get("signs")).isEmpty();
        assertThat(noHands.get("sign").has("label")).isFalse();
        assertThat(noHands.get("sign").get("reason").asText()).isEqualTo("INSUFFICIENT_LANDMARKS");

        // preview without a valid label is rejected
        ResponseEntity<String> bad = http().post().uri("/api/sessions/{id}/answer/preview", sessionId)
                .header("Authorization", "Bearer " + patient)
                .contentType(MediaType.APPLICATION_JSON).body(Map.of("labels", List.of("피자")))
                .retrieve().toEntity(String.class);
        assertThat(bad.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

        http().delete().uri("/api/sessions/{id}", sessionId).header("Authorization", "Bearer " + doctor)
                .retrieve().toEntity(String.class);
    }

    // ---- helpers ------------------------------------------------------------------------------

    /** POSTs one sign video; contentType may carry the mock's debug parameters (";labels=", ";reason="). */
    private JsonNode postSign(String sessionId, String patientToken, String contentType, Double duration)
            throws Exception {
        MultiValueMap<String, Object> form = new LinkedMultiValueMap<>();
        form.add("video", videoPart(new byte[2048], contentType));
        String uri = "/api/sessions/" + sessionId + "/sign" + (duration == null ? "" : "?duration=" + duration);
        ResponseEntity<String> res = http().post().uri(uri)
                .header("Authorization", "Bearer " + patientToken)
                .contentType(MediaType.MULTIPART_FORM_DATA).body(form)
                .retrieve().toEntity(String.class);
        assertThat(res.getStatusCode()).as(res.getBody()).isEqualTo(HttpStatus.OK);
        return json(res);
    }

    /** Multipart part with an explicit raw Content-Type header (parameters are passed through untouched). */
    private static HttpEntity<NamedBytes> videoPart(byte[] bytes, String contentType) {
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.CONTENT_TYPE, contentType);
        return new HttpEntity<>(new NamedBytes(bytes, "sign.webm"), headers);
    }

    private JsonNode json(ResponseEntity<String> res) throws Exception {
        return objectMapper.readTree(res.getBody());
    }

    private static List<String> toList(JsonNode arr) {
        java.util.ArrayList<String> out = new java.util.ArrayList<>();
        arr.forEach(n -> out.add(n.asText()));
        return out;
    }

    private EventCollector connect(String sessionId, String token) throws Exception {
        EventCollector collector = new EventCollector(objectMapper);
        StandardWebSocketClient client = new StandardWebSocketClient();
        WebSocketSession ws = client.execute(collector, new WebSocketHttpHeaders(),
                URI.create("ws://localhost:" + port + "/ws/sessions/" + sessionId + "?token=" + token)).get(5, TimeUnit.SECONDS);
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

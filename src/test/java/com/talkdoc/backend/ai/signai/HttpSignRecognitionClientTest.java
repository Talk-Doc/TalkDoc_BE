package com.talkdoc.backend.ai.signai;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.talkdoc.backend.ai.model.SignPrediction;
import com.talkdoc.backend.common.ApiException;
import com.talkdoc.backend.common.ErrorCode;
import com.talkdoc.backend.config.TalkDocProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Drives {@link HttpSignRecognitionClient} against a real (throwaway) HTTP server on an ephemeral
 * port, so the multipart request, the headers and the error mapping of POST /predict are exercised
 * end to end without any extra test dependency.
 */
class HttpSignRecognitionClientTest {

    private HttpServer server;
    private final List<Request> received = new CopyOnWriteArrayList<>();

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
            server = null;
        }
    }

    @Test
    void mapsSuccessResponseAndSendsRequestIdAndBearerToken() {
        start(exchange -> {
            String requestId = exchange.getRequestHeaders().getFirst("X-Request-ID");
            respond(exchange, 200, """
                    {"request_id":"%s","model_version":"bigru-v2-seed17","label":"배","confidence":0.94,
                     "accepted":null,"reason":"THRESHOLD_NOT_CONFIGURED","processing_ms":850.5}
                    """.formatted(requestId));
        });

        SignPrediction prediction = client("service-token", 2).predict(video(), "video/webm;codecs=vp9", 4.25);

        assertThat(prediction.modelVersion()).isEqualTo("bigru-v2-seed17");
        assertThat(prediction.label()).isEqualTo("배");
        assertThat(prediction.confidence()).isEqualTo(0.94);
        assertThat(prediction.accepted()).isNull();
        assertThat(prediction.reason()).isEqualTo("THRESHOLD_NOT_CONFIGURED");
        assertThat(prediction.processingMs()).isEqualTo(850L);

        assertThat(received).hasSize(1);
        Request request = received.get(0);
        assertThat(request.path).isEqualTo("/predict");
        assertThat(request.requestId).isNotNull();
        assertThat(UUID.fromString(request.requestId)).isNotNull(); // must be a valid UUID
        assertThat(prediction.requestId()).isEqualTo(request.requestId); // echoed back
        assertThat(request.authorization).isEqualTo("Bearer service-token");
        assertThat(request.contentType).startsWith("multipart/form-data");
        assertThat(request.body).contains("filename=\"sign.webm\"")
                .contains("Content-Type: video/webm")
                .contains("name=\"duration\"")
                .contains("4.25");
    }

    @Test
    void omitsAuthorizationHeaderWhenNoTokenConfigured() {
        start(exchange -> respond(exchange, 200,
                """
                {"request_id":"r","model_version":"m","label":"머리","confidence":0.8,"accepted":true,
                 "reason":null,"processing_ms":12}"""));

        assertThat(client(null, 2).predict(video(), "video/mp4", null).label()).isEqualTo("머리");
        assertThat(received.get(0).authorization).isNull();
        assertThat(received.get(0).body).contains("filename=\"sign.mp4\"")
                .contains("Content-Type: video/mp4")
                .doesNotContain("name=\"duration\"");
    }

    @Test
    void retriesAfterBusyAndThenSucceeds() {
        AtomicInteger calls = new AtomicInteger();
        start(exchange -> {
            if (calls.incrementAndGet() == 1) {
                exchange.getResponseHeaders().set("Retry-After", "1");
                respond(exchange, 503, """
                        {"request_id":"r1","error":{"code":"BUSY","message":"다른 영상을 처리하고 있습니다."}}""");
            } else {
                respond(exchange, 200, """
                        {"request_id":"r2","model_version":"bigru-v2-seed17","label":"배","confidence":0.91,
                         "accepted":true,"reason":null,"processing_ms":700}""");
            }
        });

        SignPrediction prediction = client(null, 2).predict(video(), "video/webm", null);

        assertThat(prediction.label()).isEqualTo("배");
        assertThat(prediction.accepted()).isTrue();
        assertThat(calls.get()).isEqualTo(2);
        // a fresh X-Request-ID per call
        assertThat(received.get(0).requestId).isNotEqualTo(received.get(1).requestId);
    }

    @Test
    void busyBeyondMaxRetriesFailsWithSignAiFailed() {
        start(exchange -> {
            exchange.getResponseHeaders().set("Retry-After", "0");
            respond(exchange, 503, """
                    {"request_id":"r","error":{"code":"BUSY","message":"다른 영상을 처리하고 있습니다."}}""");
        });

        assertThatThrownBy(() -> client(null, 1).predict(video(), "video/webm", null))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).code()).isEqualTo(ErrorCode.SIGN_AI_FAILED));
        assertThat(received).hasSize(2); // first attempt + one retry
    }

    @Test
    void videoDecodeFailureMapsToSignVideoRejected() {
        start(exchange -> respond(exchange, 422, """
                {"request_id":"r","error":{"code":"VIDEO_DECODE_FAILED","message":"영상을 디코딩하지 못했습니다."}}"""));

        assertThatThrownBy(() -> client(null, 2).predict(video(), "video/webm", null))
                .isInstanceOf(ApiException.class)
                .hasMessage("영상을 디코딩하지 못했습니다.")
                .satisfies(e -> assertThat(((ApiException) e).code()).isEqualTo(ErrorCode.SIGN_VIDEO_REJECTED));
    }

    @Test
    void unsupportedMediaTypeIsMapped() {
        start(exchange -> respond(exchange, 415, """
                {"request_id":"r","error":{"code":"UNSUPPORTED_MEDIA_TYPE","message":"WebM 또는 MP4 영상을 전송해주세요."}}"""));

        assertThatThrownBy(() -> client(null, 2).predict(video(), "video/webm", null))
                .isInstanceOf(ApiException.class)
                .hasMessage("WebM 또는 MP4 영상을 전송해주세요.")
                .satisfies(e -> assertThat(((ApiException) e).code()).isEqualTo(ErrorCode.UNSUPPORTED_MEDIA));
    }

    @Test
    void connectionRefusedMapsToSignAiFailed() throws IOException {
        int deadPort;
        try (ServerSocket socket = new ServerSocket(0)) {
            deadPort = socket.getLocalPort();
        }
        HttpSignRecognitionClient client = build("http://localhost:" + deadPort, null, 2);

        assertThatThrownBy(() -> client.predict(video(), "video/webm", null))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).code()).isEqualTo(ErrorCode.SIGN_AI_FAILED));
    }

    @Test
    void emptyVideoIsRejectedBeforeAnyCall() {
        HttpSignRecognitionClient client = build("http://localhost:1", null, 2);

        assertThatThrownBy(() -> client.predict(new byte[0], "video/webm", null))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).code()).isEqualTo(ErrorCode.INVALID_REQUEST));
    }

    // ---- helpers ------------------------------------------------------------------------------

    private interface Handler {
        void handle(HttpExchange exchange) throws IOException;
    }

    private record Request(String path, String requestId, String authorization, String contentType, String body) {
    }

    private void start(Handler handler) {
        try {
            server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
            server.createContext("/", exchange -> {
                byte[] body;
                try (InputStream in = exchange.getRequestBody()) {
                    body = in.readAllBytes();
                }
                received.add(new Request(
                        exchange.getRequestURI().getPath(),
                        exchange.getRequestHeaders().getFirst("X-Request-ID"),
                        exchange.getRequestHeaders().getFirst("Authorization"),
                        exchange.getRequestHeaders().getFirst("Content-Type"),
                        new String(body, StandardCharsets.UTF_8)));
                handler.handle(exchange);
            });
            server.start();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void respond(HttpExchange exchange, int status, String json) throws IOException {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    private HttpSignRecognitionClient client(String token, int maxRetries) {
        return build("http://localhost:" + server.getAddress().getPort(), token, maxRetries);
    }

    private static HttpSignRecognitionClient build(String baseUrl, String token, int maxRetries) {
        TalkDocProperties.SignAi signAi = new TalkDocProperties.SignAi(
                "http", baseUrl, Duration.ofSeconds(2), Duration.ofSeconds(10), token, maxRetries);
        TalkDocProperties properties = new TalkDocProperties(null, null, null, signAi, null);
        return new HttpSignRecognitionClient(properties, RestClient.builder());
    }

    private static byte[] video() {
        byte[] bytes = new byte[512];
        bytes[0] = 0x1A;
        return bytes;
    }
}

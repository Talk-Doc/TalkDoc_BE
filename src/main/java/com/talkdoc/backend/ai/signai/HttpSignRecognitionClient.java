package com.talkdoc.backend.ai.signai;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.talkdoc.backend.ai.SignRecognitionClient;
import com.talkdoc.backend.ai.model.SignPrediction;
import com.talkdoc.backend.common.ApiException;
import com.talkdoc.backend.common.ErrorCode;
import com.talkdoc.backend.config.TalkDocProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.UUID;

/**
 * Calls the TalkDoc-VisionAI service: {@code POST {base-url}/predict}, multipart {@code video}
 * (+ optional {@code duration}), one recognised word per video. Contract: docs/ai-service-contract.md.
 * The video bytes are streamed from memory and never stored.
 *
 * <p>The candidate list of the current question is deliberately NOT sent: it must not force-limit
 * the prediction.</p>
 */
@Component
@ConditionalOnProperty(prefix = "talkdoc.sign-ai", name = "mode", havingValue = "http")
public class HttpSignRecognitionClient implements SignRecognitionClient {

    private static final Logger log = LoggerFactory.getLogger(HttpSignRecognitionClient.class);

    /** Own mapper: the AI speaks snake_case regardless of how the application ObjectMapper is configured. */
    private static final JsonMapper MAPPER = JsonMapper.builder()
            .propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .build();

    private static final Duration DEFAULT_RETRY_AFTER = Duration.ofSeconds(2);
    private static final Duration MAX_RETRY_AFTER = Duration.ofSeconds(10);

    private final RestClient restClient;
    private final String token;
    private final int maxRetries;

    public HttpSignRecognitionClient(TalkDocProperties properties, RestClient.Builder builder) {
        TalkDocProperties.SignAi cfg = properties.signAi();
        // Flask 개발 서버는 HTTP/1.1 전용이라 JDK HttpClient 기본값(h2c 업그레이드 시도)을 거부하므로 1.1로 고정한다.
        HttpClient httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(cfg.connectTimeout())
                .build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(cfg.readTimeout());
        this.restClient = builder.baseUrl(cfg.baseUrl()).requestFactory(factory).build();
        this.token = cfg.token() == null || cfg.token().isBlank() ? null : cfg.token().strip();
        this.maxRetries = Math.max(0, cfg.maxRetries());
    }

    @Override
    public SignPrediction predict(byte[] video, String mimeType, Double durationSeconds) {
        if (video == null || video.length == 0) {
            throw new ApiException(ErrorCode.INVALID_REQUEST, "영상이 비어 있습니다.");
        }
        String mime = normalizeMime(mimeType);
        String filename = "video/mp4".equals(mime) ? "sign.mp4" : "sign.webm";

        for (int attempt = 0; ; attempt++) {
            String requestId = UUID.randomUUID().toString();
            try {
                return call(requestId, video, mime, filename, durationSeconds);
            } catch (RestClientResponseException e) {
                AiError error = parseError(e.getResponseBodyAsString());
                if (isBusy(e, error) && attempt < maxRetries) {
                    log.debug("Sign AI busy (request {}), retrying (attempt {}/{})", requestId, attempt + 1, maxRetries);
                    pause(retryAfter(e));
                    continue;
                }
                throw translate(e, error);
            } catch (ApiException e) {
                throw e;
            } catch (Exception e) {
                log.warn("Sign AI call failed (request {}): {}", requestId, e.getClass().getSimpleName());
                throw new ApiException(ErrorCode.SIGN_AI_FAILED, "수어 인식 서비스에 연결하지 못했습니다.", e);
            }
        }
    }

    private SignPrediction call(String requestId, byte[] video, String mime, String filename, Double duration) {
        HttpHeaders videoHeaders = new HttpHeaders();
        videoHeaders.setContentType(MediaType.parseMediaType(mime));
        MultiValueMap<String, Object> form = new LinkedMultiValueMap<>();
        form.add("video", new HttpEntity<>(new NamedResource(video, filename), videoHeaders));
        if (duration != null) {
            form.add("duration", String.valueOf(duration));
        }

        String body = restClient.post()
                .uri("/predict")
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .header("X-Request-ID", requestId)
                .headers(h -> {
                    if (token != null) h.setBearerAuth(token);
                })
                .body(form)
                .retrieve()
                .body(String.class);

        SignPrediction prediction = read(body);
        if (prediction == null) {
            throw new ApiException(ErrorCode.SIGN_AI_FAILED, "수어 인식 서비스가 빈 응답을 반환했습니다.");
        }
        log.debug("Sign AI request {} → label={} confidence={} accepted={} reason={} processing_ms={}",
                prediction.requestId(), prediction.label(), prediction.confidence(),
                prediction.accepted(), prediction.reason(), prediction.processingMs());
        return prediction;
    }

    private static SignPrediction read(String body) {
        if (body == null || body.isBlank()) return null;
        try {
            return MAPPER.readValue(body, SignPrediction.class);
        } catch (Exception e) {
            throw new ApiException(ErrorCode.SIGN_AI_FAILED, "수어 인식 서비스 응답을 해석할 수 없습니다.", e);
        }
    }

    private static AiError parseError(String body) {
        if (body == null || body.isBlank()) return null;
        try {
            ErrorEnvelope envelope = MAPPER.readValue(body, ErrorEnvelope.class);
            return envelope == null ? null : envelope.error();
        } catch (Exception e) {
            return null;
        }
    }

    private static boolean isBusy(RestClientResponseException e, AiError error) {
        return e.getStatusCode().value() == 503 && error != null && "BUSY".equals(error.code());
    }

    private ApiException translate(RestClientResponseException e, AiError error) {
        int status = e.getStatusCode().value();
        String aiMessage = error == null || error.message() == null || error.message().isBlank()
                ? null : error.message().strip();
        log.warn("Sign AI returned status {} ({})", status, error == null ? "-" : error.code());
        return switch (status) {
            case 400 -> new ApiException(ErrorCode.INVALID_REQUEST,
                    aiMessage == null ? ErrorCode.INVALID_REQUEST.defaultMessage() : aiMessage, e);
            case 401 -> new ApiException(ErrorCode.SIGN_AI_FAILED,
                    "수어 인식 서비스 인증에 실패했습니다. 서비스 토큰(TALKDOC_SIGN_AI_TOKEN) 설정을 확인해주세요.", e);
            case 413 -> new ApiException(ErrorCode.FILE_TOO_LARGE,
                    aiMessage == null ? ErrorCode.FILE_TOO_LARGE.defaultMessage() : aiMessage, e);
            case 415 -> new ApiException(ErrorCode.UNSUPPORTED_MEDIA,
                    aiMessage == null ? ErrorCode.UNSUPPORTED_MEDIA.defaultMessage() : aiMessage, e);
            case 422 -> new ApiException(ErrorCode.SIGN_VIDEO_REJECTED,
                    aiMessage == null ? ErrorCode.SIGN_VIDEO_REJECTED.defaultMessage() : aiMessage, e);
            default -> new ApiException(ErrorCode.SIGN_AI_FAILED,
                    "수어 인식 서비스 오류 (HTTP " + status + (error == null ? "" : ", " + error.code()) + ")", e);
        };
    }

    private static Duration retryAfter(RestClientResponseException e) {
        HttpHeaders headers = e.getResponseHeaders();
        String raw = headers == null ? null : headers.getFirst("Retry-After");
        if (raw != null) {
            try {
                long seconds = Long.parseLong(raw.strip());
                if (seconds >= 0) {
                    Duration d = Duration.ofSeconds(seconds);
                    return d.compareTo(MAX_RETRY_AFTER) > 0 ? MAX_RETRY_AFTER : d;
                }
            } catch (NumberFormatException ignored) {
                // Retry-After may also be an HTTP-date; fall back to the default
            }
        }
        return DEFAULT_RETRY_AFTER;
    }

    private static void pause(Duration duration) {
        if (duration.isZero() || duration.isNegative()) return;
        try {
            Thread.sleep(duration.toMillis());
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new ApiException(ErrorCode.SIGN_AI_FAILED, "수어 인식 요청이 중단되었습니다.", ie);
        }
    }

    /** The AI only accepts video/webm and video/mp4; anything else would come back as 415. */
    private static String normalizeMime(String mimeType) {
        String mime = mimeType == null ? "" : mimeType.split(";")[0].strip().toLowerCase();
        return mime.contains("mp4") ? "video/mp4" : "video/webm";
    }

    /** Error body of the AI service: {@code {"request_id": "...", "error": {"code": "...", "message": "..."}}}. */
    record ErrorEnvelope(String requestId, AiError error) {
    }

    record AiError(String code, String message) {
    }

    /** ByteArrayResource with a filename so the multipart part carries Content-Disposition filename. */
    static final class NamedResource extends ByteArrayResource {
        private final String filename;

        NamedResource(byte[] bytes, String filename) {
            super(bytes);
            this.filename = filename;
        }

        @Override
        public String getFilename() {
            return filename;
        }
    }
}

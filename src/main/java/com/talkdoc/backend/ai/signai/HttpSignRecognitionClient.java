package com.talkdoc.backend.ai.signai;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.talkdoc.backend.ai.SignRecognitionClient;
import com.talkdoc.backend.ai.model.SignResult;
import com.talkdoc.backend.common.ApiException;
import com.talkdoc.backend.common.ErrorCode;
import com.talkdoc.backend.config.TalkDocProperties;
import com.talkdoc.backend.question.Intent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.net.http.HttpClient;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Calls the TalkDoc_AI service: POST {base-url}/recognize (multipart: video, intent, candidates).
 * Contract: docs/ai-service-contract.md. The video bytes are streamed from memory and never stored.
 */
@Component
@ConditionalOnProperty(prefix = "talkdoc.sign-ai", name = "mode", havingValue = "http")
public class HttpSignRecognitionClient implements SignRecognitionClient {

    private static final Logger log = LoggerFactory.getLogger(HttpSignRecognitionClient.class);

    private final RestClient restClient;

    public HttpSignRecognitionClient(TalkDocProperties properties, RestClient.Builder builder) {
        TalkDocProperties.SignAi cfg = properties.signAi();
        HttpClient httpClient = HttpClient.newBuilder().connectTimeout(cfg.timeout()).build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(cfg.timeout());
        this.restClient = builder.baseUrl(cfg.baseUrl()).requestFactory(factory).build();
    }

    @Override
    public List<SignResult> recognize(byte[] video, String mimeType, List<Intent> intents, List<String> candidates) {
        if (video == null || video.length == 0) {
            throw new ApiException(ErrorCode.INVALID_REQUEST, "영상이 비어 있습니다.");
        }
        String mime = mimeType == null || mimeType.isBlank() ? "video/webm" : mimeType.split(";")[0].strip();
        String filename = "sign." + (mime.contains("mp4") ? "mp4" : "webm");

        MultiValueMap<String, Object> form = new LinkedMultiValueMap<>();
        form.add("video", new NamedResource(video, filename));
        form.add("intent", intents.stream().map(Enum::name).collect(Collectors.joining(",")));
        form.add("candidates", String.join(",", candidates));

        SignAiResponse response;
        try {
            response = restClient.post()
                    .uri("/recognize")
                    .contentType(MediaType.MULTIPART_FORM_DATA)
                    .body(form)
                    .retrieve()
                    .body(SignAiResponse.class);
        } catch (RestClientResponseException e) {
            log.warn("Sign AI returned status {}", e.getStatusCode().value());
            throw new ApiException(ErrorCode.SIGN_AI_FAILED, "수어 인식 서비스 오류 (HTTP " + e.getStatusCode().value() + ")", e);
        } catch (Exception e) {
            log.warn("Sign AI call failed: {}", e.getClass().getSimpleName());
            throw new ApiException(ErrorCode.SIGN_AI_FAILED, "수어 인식 서비스에 연결하지 못했습니다.", e);
        }
        if (response == null || response.signs() == null) {
            return List.of();
        }
        List<SignResult> out = new ArrayList<>();
        for (SignAiSign sign : response.signs()) {
            if (sign == null || sign.label() == null) continue;
            if (!candidates.isEmpty() && !candidates.contains(sign.label())) {
                log.warn("Sign AI returned label outside candidates, dropped: {}", sign.label());
                continue;
            }
            out.add(new SignResult(sign.label(), sign.confidence() == null ? 0.0 : sign.confidence()));
        }
        log.debug("Sign AI recognised {} signs from {} bytes ({} segments)", out.size(), video.length, response.segments());
        return out;
    }

    /** Response of POST /recognize. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record SignAiResponse(List<SignAiSign> signs, Integer segments) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record SignAiSign(String label, Double confidence) {
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

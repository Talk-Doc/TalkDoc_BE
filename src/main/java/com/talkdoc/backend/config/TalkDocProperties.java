package com.talkdoc.backend.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Root of all "talkdoc.*" settings. Values come from application.yml (see there for defaults).
 */
@ConfigurationProperties(prefix = "talkdoc")
public record TalkDocProperties(
        Session session,
        Sign sign,
        Ai ai,
        SignAi signAi,
        Websocket websocket
) {

    public record Session(Duration ttl) {
        public Session {
            if (ttl == null) ttl = Duration.ofHours(2);
        }
    }

    public record Sign(double confidenceThreshold) {
    }

    public record Ai(String provider, Duration timeout, Gemini gemini) {
        public Ai {
            if (provider == null || provider.isBlank()) provider = "mock";
            if (timeout == null) timeout = Duration.ofSeconds(15);
        }

        public boolean isMock() {
            return "mock".equalsIgnoreCase(provider);
        }
    }

    /**
     * @param thinkingLevel Gemini 3.x 의 thinkingConfig.thinkingLevel ("low" 권장). 비우면 모델 기본값(수천 토큰의
     *                      thinking → 호출당 20~30초, maxOutputTokens 소진)을 그대로 쓴다. tts 모델에는 적용하지 않는다.
     */
    public record Gemini(String apiKey, String baseUrl, Models models, String thinkingLevel) {
        // 생성자를 하나만 둬야 Spring Boot 가 생성자 바인딩으로 값을 채운다 (둘이면 전부 null 이 됨).
        public record Models(String stt, String llm, String tts) {
        }
    }

    /**
     * TalkDoc-VisionAI ({@code POST {base-url}/predict}, Flask, 기본 포트 5001) 연동 설정.
     *
     * @param token    서비스 토큰(TALKDOC_SIGN_AI_TOKEN). 의사/환자 토큰과 무관하며, 비어 있으면 Authorization 헤더를 보내지 않는다.
     * @param maxRetries 503 BUSY 응답에 대한 재시도 횟수 (Retry-After 만큼 대기 후 재요청)
     */
    public record SignAi(String mode, String baseUrl, Duration connectTimeout, Duration readTimeout,
                         String token, int maxRetries) {
        public SignAi {
            if (mode == null || mode.isBlank()) mode = "mock";
            if (baseUrl == null || baseUrl.isBlank()) baseUrl = "http://localhost:5001";
            if (connectTimeout == null) connectTimeout = Duration.ofSeconds(5);
            if (readTimeout == null) readTimeout = Duration.ofSeconds(60);
            if (maxRetries < 0) maxRetries = 2;
        }

        public boolean isMock() {
            return "mock".equalsIgnoreCase(mode);
        }
    }

    public record Websocket(String allowedOrigins) {
        public String[] allowedOriginArray() {
            if (allowedOrigins == null || allowedOrigins.isBlank()) return new String[]{"*"};
            return allowedOrigins.split("\\s*,\\s*");
        }
    }
}

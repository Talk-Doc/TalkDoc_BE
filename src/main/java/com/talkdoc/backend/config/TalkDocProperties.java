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

    public record Gemini(String apiKey, String baseUrl, Models models) {
        public record Models(String stt, String llm, String tts) {
        }
    }

    public record SignAi(String mode, String baseUrl, Duration timeout) {
        public SignAi {
            if (mode == null || mode.isBlank()) mode = "mock";
            if (timeout == null) timeout = Duration.ofSeconds(10);
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

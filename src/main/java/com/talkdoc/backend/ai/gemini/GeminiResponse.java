package com.talkdoc.backend.ai.gemini;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Base64;
import java.util.List;

/** Minimal projection of the generateContent response. Field names are Gemini's camelCase. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record GeminiResponse(@JsonProperty("candidates") List<Candidate> candidates) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Candidate(@JsonProperty("content") Content content,
                            @JsonProperty("finishReason") String finishReason) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Content(@JsonProperty("parts") List<Part> parts) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Part(@JsonProperty("text") String text,
                       @JsonProperty("inlineData") InlineData inlineData) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record InlineData(@JsonProperty("mimeType") String mimeType,
                             @JsonProperty("data") String data) {
    }

    /** Concatenated text of the first candidate, or empty string. */
    public String firstText() {
        StringBuilder sb = new StringBuilder();
        for (Part part : firstParts()) {
            if (part.text() != null) sb.append(part.text());
        }
        return sb.toString();
    }

    /** Decoded bytes of the first inline part of the first candidate, or null. */
    public byte[] firstInlineData() {
        for (Part part : firstParts()) {
            if (part.inlineData() != null && part.inlineData().data() != null) {
                return Base64.getDecoder().decode(part.inlineData().data());
            }
        }
        return null;
    }

    public String firstInlineMimeType() {
        for (Part part : firstParts()) {
            if (part.inlineData() != null) return part.inlineData().mimeType();
        }
        return null;
    }

    private List<Part> firstParts() {
        if (candidates == null || candidates.isEmpty()) return List.of();
        Candidate c = candidates.get(0);
        if (c == null || c.content() == null || c.content().parts() == null) return List.of();
        return c.content().parts();
    }
}

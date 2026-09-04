package com.talkdoc.backend.ai.gemini;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class GeminiTtsAndSttTest {

    @Test
    void wavEncoderWritesHeader() {
        byte[] wav = WavEncoder.pcm16ToWav(new byte[100], 24_000, 1);
        assertThat(wav).hasSize(144);
        assertThat(new String(wav, 0, 4, StandardCharsets.US_ASCII)).isEqualTo("RIFF");
        assertThat(new String(wav, 8, 4, StandardCharsets.US_ASCII)).isEqualTo("WAVE");
    }

    @Test
    void sampleRateParsedFromMime() {
        assertThat(GeminiTtsClient.sampleRateOf("audio/L16;codec=pcm;rate=24000")).isEqualTo(24_000);
        assertThat(GeminiTtsClient.sampleRateOf("audio/L16;rate=16000")).isEqualTo(16_000);
        assertThat(GeminiTtsClient.sampleRateOf(null)).isEqualTo(24_000);
    }

    @Test
    void sttMimeNormalised() {
        assertThat(GeminiSttClient.normalizeMime("audio/webm;codecs=opus")).isEqualTo("audio/webm");
        assertThat(GeminiSttClient.normalizeMime(null)).isEqualTo("audio/webm");
        assertThat(GeminiSttClient.normalizeMime("AUDIO/WAV")).isEqualTo("audio/wav");
    }
}

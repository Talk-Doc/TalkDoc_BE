package com.talkdoc.backend.ai.model;

/** Synthesised speech. mimeType e.g. "audio/mpeg" or "audio/wav". */
public record TtsAudio(byte[] data, String mimeType) {
}

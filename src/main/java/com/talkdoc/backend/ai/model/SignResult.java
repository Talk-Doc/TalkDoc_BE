package com.talkdoc.backend.ai.model;

/** Raw Vision AI output for one segment, before the confidence threshold is applied. */
public record SignResult(String label, double confidence) {
}

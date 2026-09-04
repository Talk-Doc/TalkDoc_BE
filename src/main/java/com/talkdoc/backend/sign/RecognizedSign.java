package com.talkdoc.backend.sign;

/**
 * A single recognised sign after the confidence threshold has been applied.
 * accepted == false means the frontend must ask the patient to sign again (FR-10).
 */
public record RecognizedSign(String label, double confidence, boolean accepted) {
}

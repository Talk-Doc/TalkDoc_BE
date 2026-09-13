package com.talkdoc.backend.sign;

/**
 * The single sign recognised from one video, after the accepted-verdict has been resolved
 * (the AI's own verdict when it has one, otherwise {@code talkdoc.sign.confidence-threshold}).
 * <p>accepted == false means the frontend must ask the patient to record again (FR-10):
 * reason LOW_CONFIDENCE → just re-record, INSUFFICIENT_LANDMARKS → re-record with hands and
 * upper body visible. label/confidence are null when nothing could be recognised.</p>
 *
 * @param reason null when accepted, otherwise LOW_CONFIDENCE or INSUFFICIENT_LANDMARKS
 */
public record RecognizedSign(String label, Double confidence, boolean accepted, String reason) {
}

package com.talkdoc.backend.ai.model;

/**
 * Raw TalkDoc-VisionAI output for one video: {@code POST /predict} returns exactly one word.
 * All fields except {@code requestId} may be null, mirroring the service contract:
 * <ul>
 *   <li>{@code accepted == null} with {@code reason == "THRESHOLD_NOT_CONFIGURED"} → the AI server has no
 *       threshold configured; the backend applies {@code talkdoc.sign.confidence-threshold} itself.</li>
 *   <li>{@code accepted == false} with {@code reason == "INSUFFICIENT_LANDMARKS"} → hands/upper body were not
 *       visible ({@code label} and {@code confidence} are null); the patient must re-record.</li>
 *   <li>{@code accepted == false} with {@code reason == "LOW_CONFIDENCE"} → re-record.</li>
 * </ul>
 *
 * @param requestId    echo of the X-Request-ID the backend generated for this call
 * @param modelVersion e.g. "bigru-v2-seed17"
 * @param label        the recognised word, or null when nothing could be recognised
 * @param confidence   0..1, or null when there is no label
 * @param accepted     the AI's own verdict, or null when it has no threshold configured
 * @param reason       null, LOW_CONFIDENCE, INSUFFICIENT_LANDMARKS or THRESHOLD_NOT_CONFIGURED
 * @param processingMs server-side inference time in milliseconds
 */
public record SignPrediction(
        String requestId,
        String modelVersion,
        String label,
        Double confidence,
        Boolean accepted,
        String reason,
        Long processingMs
) {
}

package com.talkdoc.backend.sign;

import java.time.Instant;

/**
 * One stored sign-recognition result, so that {@code /answer/preview} can be given recognition ids
 * instead of raw labels and the doctor-facing session detail can show what the patient actually signed.
 *
 * <p>Only recognitions made against a pending question are stored; a call that passes an explicit
 * {@code intent} without a pending question has nowhere to attach and is not persisted.</p>
 *
 * @param questionVersion the question version this recognition belongs to
 * @param accepted        false when the patient must re-record; such a recognition cannot be used for a preview
 * @param reason          null when accepted, otherwise LOW_CONFIDENCE / INSUFFICIENT_LANDMARKS
 */
public record Recognition(
        String recognitionId,
        String questionId,
        int questionVersion,
        String label,
        Double confidence,
        boolean accepted,
        String reason,
        String modelVersion,
        String requestId,
        Instant recognizedAt
) {

    public Recognition {
        if (questionVersion <= 0) {
            questionVersion = 1;
        }
    }
}

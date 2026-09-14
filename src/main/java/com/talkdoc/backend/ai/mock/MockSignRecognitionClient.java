package com.talkdoc.backend.ai.mock;

import com.talkdoc.backend.ai.SignRecognitionClient;
import com.talkdoc.backend.ai.model.SignPrediction;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Deterministic stand-in for the TalkDoc-VisionAI service ({@code POST /predict}): one word per video.
 * <ul>
 *   <li>Default: label "배", confidence 0.94, accepted null, reason THRESHOLD_NOT_CONFIGURED
 *       (so SignService applies {@code talkdoc.sign.confidence-threshold} itself, like the real server).</li>
 *   <li>Debug override via the mimeType parameters (quoted values are accepted):
 *     <ul>
 *       <li>{@code video/webm;labels=머리} → label 머리, confidence 0.9. With several comma-separated
 *           labels only the first one is used, because the real service returns one word per video.</li>
 *       <li>{@code video/webm;labels=머리;confidence=0.5} → that confidence.</li>
 *       <li>{@code video/webm;reason=INSUFFICIENT_LANDMARKS} → label null, confidence null, accepted false,
 *           that reason.</li>
 *     </ul>
 *   </li>
 * </ul>
 */
@Component
@ConditionalOnProperty(prefix = "talkdoc.sign-ai", name = "mode", havingValue = "mock", matchIfMissing = true)
public class MockSignRecognitionClient implements SignRecognitionClient {

    static final String LABELS_PARAM = "labels";
    static final String CONFIDENCE_PARAM = "confidence";
    static final String REASON_PARAM = "reason";

    public static final String DEFAULT_LABEL = "배";
    public static final double DEFAULT_CONFIDENCE = 0.94;
    public static final String THRESHOLD_NOT_CONFIGURED = "THRESHOLD_NOT_CONFIGURED";
    public static final String MODEL_VERSION = "mock";

    @Override
    public SignPrediction predict(byte[] video, String mimeType, Double durationSeconds) {
        String requestId = UUID.randomUUID().toString();

        String reason = param(mimeType, REASON_PARAM);
        if (reason != null && !reason.isBlank()) {
            return new SignPrediction(requestId, MODEL_VERSION, null, null, false, reason.strip(), 5L);
        }

        String labels = param(mimeType, LABELS_PARAM);
        String label = DEFAULT_LABEL;
        double confidence = DEFAULT_CONFIDENCE;
        if (labels != null && !labels.isBlank()) {
            // 실서비스는 영상 1개당 단어 1개만 돌려주므로 첫 번째 라벨만 사용한다.
            String first = labels.split(",")[0].strip();
            if (!first.isEmpty()) {
                label = first;
                confidence = 0.9;
            }
        }
        String explicitConfidence = param(mimeType, CONFIDENCE_PARAM);
        if (explicitConfidence != null && !explicitConfidence.isBlank()) {
            try {
                confidence = Double.parseDouble(explicitConfidence.strip());
            } catch (NumberFormatException ignored) {
                // keep the default
            }
        }
        return new SignPrediction(requestId, MODEL_VERSION, label, confidence, null, THRESHOLD_NOT_CONFIGURED, 5L);
    }

    /** Reads a {@code ;name=value} parameter out of a content type, tolerating quoted values. */
    private static String param(String mimeType, String name) {
        if (mimeType == null) return null;
        String marker = ";" + name + "=";
        int start = mimeType.indexOf(marker);
        if (start < 0) return null;
        String value = mimeType.substring(start + marker.length());
        int end = value.indexOf(';');
        if (end >= 0) value = value.substring(0, end);
        value = value.strip();
        if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
            value = value.substring(1, value.length() - 1); // quoted parameter value
        }
        return value;
    }
}

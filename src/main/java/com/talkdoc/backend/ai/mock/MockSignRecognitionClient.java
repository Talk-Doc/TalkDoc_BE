package com.talkdoc.backend.ai.mock;

import com.talkdoc.backend.ai.SignRecognitionClient;
import com.talkdoc.backend.ai.model.SignResult;
import com.talkdoc.backend.question.Intent;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Deterministic stand-in for the TalkDoc_AI service.
 * <ul>
 *   <li>Prefers ["배", "아프다"] when both are candidates, otherwise the first two candidates (0.94 / 0.91).</li>
 *   <li>Debug override: a mimeType such as {@code video/webm;labels=머리,어지럽다} returns exactly those labels
 *       with confidence 0.9 (labels outside the candidate list are still returned so threshold logic can be tested).</li>
 *   <li>Empty candidates → empty result.</li>
 * </ul>
 */
@Component
@ConditionalOnProperty(prefix = "talkdoc.sign-ai", name = "mode", havingValue = "mock", matchIfMissing = true)
public class MockSignRecognitionClient implements SignRecognitionClient {

    static final String LABELS_PARAM = ";labels=";

    @Override
    public List<SignResult> recognize(byte[] video, String mimeType, List<Intent> intents, List<String> candidates) {
        if (mimeType != null && mimeType.contains(LABELS_PARAM)) {
            String spec = mimeType.substring(mimeType.indexOf(LABELS_PARAM) + LABELS_PARAM.length());
            int end = spec.indexOf(';');
            if (end >= 0) spec = spec.substring(0, end);
            spec = spec.strip();
            if (spec.length() >= 2 && spec.startsWith("\"") && spec.endsWith("\"")) {
                spec = spec.substring(1, spec.length() - 1); // quoted parameter value
            }
            List<SignResult> out = new ArrayList<>();
            for (String label : spec.split(",")) {
                if (!label.isBlank()) out.add(new SignResult(label.strip(), 0.9));
            }
            return out;
        }
        if (candidates == null || candidates.isEmpty()) {
            return List.of();
        }
        if (candidates.contains("배") && candidates.contains("아프다")) {
            return List.of(new SignResult("배", 0.94), new SignResult("아프다", 0.91));
        }
        List<SignResult> out = new ArrayList<>();
        double[] confidences = {0.94, 0.91};
        for (int i = 0; i < Math.min(2, candidates.size()); i++) {
            out.add(new SignResult(candidates.get(i), confidences[i]));
        }
        return out;
    }
}

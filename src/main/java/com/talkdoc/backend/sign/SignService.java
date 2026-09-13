package com.talkdoc.backend.sign;

import com.talkdoc.backend.ai.SignRecognitionClient;
import com.talkdoc.backend.ai.model.SignPrediction;
import com.talkdoc.backend.common.ApiException;
import com.talkdoc.backend.common.ErrorCode;
import com.talkdoc.backend.config.TalkDocProperties;
import com.talkdoc.backend.question.Intent;
import com.talkdoc.backend.question.PendingQuestion;
import com.talkdoc.backend.session.Session;
import com.talkdoc.backend.session.SessionService;
import com.talkdoc.backend.sign.dto.SignResponse;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;

/**
 * Patient sign-video recognition against TalkDoc-VisionAI ({@code POST /predict}: one word per video).
 * Nothing is stored: the caller (frontend) collects accepted labels across one or more calls and
 * later submits them to /answer/preview or /answer/confirm.
 */
@Service
public class SignService {

    /** Max recording length accepted by the AI service. */
    static final double MAX_DURATION_SECONDS = 20.0;

    static final String REASON_LOW_CONFIDENCE = "LOW_CONFIDENCE";

    private final SessionService sessionService;
    private final SignRecognitionClient signRecognitionClient;
    private final TalkDocProperties properties;

    public SignService(SessionService sessionService,
                        SignRecognitionClient signRecognitionClient,
                        TalkDocProperties properties) {
        this.sessionService = sessionService;
        this.signRecognitionClient = signRecognitionClient;
        this.properties = properties;
    }

    public SignResponse recognize(String sessionId, MultipartFile video, String intentName, Double durationSeconds) {
        Session session = sessionService.requireActive(sessionId);

        if (video == null || video.isEmpty()) {
            throw new ApiException(ErrorCode.INVALID_REQUEST, "video는 필수입니다.");
        }
        String contentType = video.getContentType();
        if (contentType == null || !contentType.startsWith("video/")) {
            throw new ApiException(ErrorCode.UNSUPPORTED_MEDIA);
        }
        if (durationSeconds != null
                && (durationSeconds.isNaN() || durationSeconds <= 0 || durationSeconds > MAX_DURATION_SECONDS)) {
            throw new ApiException(ErrorCode.INVALID_REQUEST,
                    "duration은 0보다 크고 " + (int) MAX_DURATION_SECONDS + " 이하인 초 단위 값이어야 합니다.");
        }

        PendingQuestion currentQuestion = session.currentQuestion();
        List<Intent> intents = resolveIntents(intentName, currentQuestion);
        // candidates 는 프론트 안내용 정보일 뿐, AI 예측을 제한하지 않는다 (AI 계약서 규칙).
        List<String> candidates = Intent.candidatesFor(intents);
        String questionId = currentQuestion == null ? null : currentQuestion.questionId();

        byte[] bytes;
        try {
            bytes = video.getBytes();
        } catch (IOException e) {
            throw new ApiException(ErrorCode.INVALID_REQUEST, "영상 파일을 읽을 수 없습니다.", e);
        }

        SignPrediction prediction = signRecognitionClient.predict(bytes, contentType, durationSeconds);
        RecognizedSign sign = resolve(prediction);

        boolean hasLabel = sign.label() != null;
        List<RecognizedSign> signs = hasLabel ? List.of(sign) : List.of();
        boolean allAccepted = hasLabel && sign.accepted();
        List<String> acceptedLabels = allAccepted ? List.of(sign.label()) : List.of();

        return new SignResponse(questionId, intents, candidates, sign, signs, allAccepted, acceptedLabels,
                prediction.modelVersion(), prediction.requestId(), prediction.processingMs());
    }

    /**
     * Applies the backend confidence threshold when the AI has none configured
     * (accepted == null, reason THRESHOLD_NOT_CONFIGURED); otherwise the AI's verdict wins.
     */
    private RecognizedSign resolve(SignPrediction prediction) {
        String label = prediction.label();
        Double confidence = prediction.confidence();
        if (label == null) {
            boolean accepted = Boolean.TRUE.equals(prediction.accepted());
            return new RecognizedSign(null, confidence, accepted, prediction.reason());
        }
        if (prediction.accepted() != null) {
            boolean accepted = prediction.accepted();
            return new RecognizedSign(label, confidence, accepted, accepted ? null : prediction.reason());
        }
        double threshold = properties.sign().confidenceThreshold();
        boolean accepted = confidence != null && confidence >= threshold;
        return new RecognizedSign(label, confidence, accepted, accepted ? null : REASON_LOW_CONFIDENCE);
    }

    private List<Intent> resolveIntents(String intentName, PendingQuestion currentQuestion) {
        if (intentName != null && !intentName.isBlank()) {
            try {
                return List.of(Intent.valueOf(intentName));
            } catch (IllegalArgumentException e) {
                throw new ApiException(ErrorCode.INVALID_REQUEST, "알 수 없는 intent입니다: " + intentName);
            }
        }
        if (currentQuestion == null) {
            throw new ApiException(ErrorCode.NO_PENDING_QUESTION);
        }
        return currentQuestion.intents();
    }
}

package com.talkdoc.backend.sign;

import com.talkdoc.backend.ai.SignRecognitionClient;
import com.talkdoc.backend.ai.model.SignResult;
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
 * Patient sign-video recognition. Nothing is stored: the caller (frontend) collects accepted
 * labels across one or more calls and later submits them to /answer/preview or /answer/confirm.
 */
@Service
public class SignService {

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

    public SignResponse recognize(String sessionId, MultipartFile video, String intentName) {
        Session session = sessionService.requireActive(sessionId);

        if (video == null || video.isEmpty()) {
            throw new ApiException(ErrorCode.INVALID_REQUEST, "video는 필수입니다.");
        }
        String contentType = video.getContentType();
        if (contentType == null || !contentType.startsWith("video/")) {
            throw new ApiException(ErrorCode.UNSUPPORTED_MEDIA);
        }

        PendingQuestion currentQuestion = session.currentQuestion();
        List<Intent> intents = resolveIntents(intentName, currentQuestion);
        List<String> candidates = Intent.candidatesFor(intents);
        String questionId = currentQuestion == null ? null : currentQuestion.questionId();

        List<RecognizedSign> signs;
        if (candidates.isEmpty()) {
            signs = List.of();
        } else {
            byte[] bytes;
            try {
                bytes = video.getBytes();
            } catch (IOException e) {
                throw new ApiException(ErrorCode.INVALID_REQUEST, "영상 파일을 읽을 수 없습니다.", e);
            }
            List<SignResult> results = signRecognitionClient.recognize(bytes, contentType, intents, candidates);
            double threshold = properties.sign().confidenceThreshold();
            signs = results.stream()
                    .map(r -> new RecognizedSign(r.label(), r.confidence(),
                            r.confidence() >= threshold && candidates.contains(r.label())))
                    .toList();
        }

        boolean allAccepted = !signs.isEmpty() && signs.stream().allMatch(RecognizedSign::accepted);
        List<String> acceptedLabels = signs.stream()
                .filter(RecognizedSign::accepted)
                .map(RecognizedSign::label)
                .toList();

        return new SignResponse(questionId, intents, candidates, signs, allAccepted, acceptedLabels);
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

package com.talkdoc.backend.question;

import com.talkdoc.backend.auth.RequireRole;
import com.talkdoc.backend.auth.Role;
import com.talkdoc.backend.question.dto.QuestionResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * Doctor question intake: audio (STT) or plain text, classified into intents for the patient's
 * sign-recognition step.
 */
@RestController
@RequestMapping("/api/sessions/{sessionId}/question")
@Tag(name = "Question", description = "의사 질문 등록 (음성/텍스트 → 의도 분석)")
public class QuestionController {

    private final QuestionService questionService;

    public QuestionController(QuestionService questionService) {
        this.questionService = questionService;
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @RequireRole(Role.DOCTOR)
    @Operation(
            summary = "질문 등록",
            description = "의사가 음성(audio) 또는 텍스트(text)로 질문을 등록합니다. text가 있으면 STT를 건너뜁니다. " +
                    "doctor_token 필요."
    )
    public QuestionResponse post(@PathVariable String sessionId,
                                  @RequestPart(value = "audio", required = false) MultipartFile audio,
                                  @RequestParam(value = "text", required = false) String text) {
        return questionService.postQuestion(sessionId, audio, text);
    }
}

package com.talkdoc.backend.question;

import com.talkdoc.backend.auth.RequireRole;
import com.talkdoc.backend.auth.Role;
import com.talkdoc.backend.question.dto.QuestionResponse;
import com.talkdoc.backend.question.dto.UpdateQuestionRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Editing of the question that is already pending. Kept apart from {@link QuestionController}
 * (mapped at .../question) because the edit endpoint addresses a specific question id.
 */
@RestController
@RequestMapping("/api/sessions/{sessionId}/questions")
@Tag(name = "Question", description = "의사 질문 등록 (음성/텍스트 → 의도 분석)")
public class QuestionEditController {

    private final QuestionService questionService;

    public QuestionEditController(QuestionService questionService) {
        this.questionService = questionService;
    }

    @PatchMapping("/{questionId}")
    @RequireRole(Role.DOCTOR)
    @Operation(
            summary = "질문 수정",
            description = """
                    대기 중인 질문의 문장을 고칩니다. question_id는 그대로 두고 version만 1 올리며,
                    의도(intents)·후보 단어(candidates)는 새 문장으로 다시 분석합니다.
                    body: {"text": "...", "version": <현재 버전>}.
                    version이 현재 버전과 다르면 409 VERSION_CONFLICT,
                    이미 확정 답변이 있는 질문이면 409 QUESTION_ALREADY_ANSWERED,
                    그 외 대기 중인 질문이 아니면 404 QUESTION_NOT_FOUND 입니다.
                    이전 버전으로 만들어 둔 답변 초안(draft)은 INVALIDATED 되어 확정할 수 없게 되고,
                    기존 수어 인식 기록은 그대로 보존됩니다.
                    WebSocket으로 QUESTION_UPDATED 이벤트가 전송됩니다. doctor_token 필요."""
    )
    public QuestionResponse update(@PathVariable String sessionId,
                                    @PathVariable String questionId,
                                    @Valid @RequestBody UpdateQuestionRequest request) {
        return questionService.updateQuestion(sessionId, questionId, request.text(), request.version());
    }
}

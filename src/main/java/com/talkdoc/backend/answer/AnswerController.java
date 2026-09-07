package com.talkdoc.backend.answer;

import com.talkdoc.backend.ai.model.TtsAudio;
import com.talkdoc.backend.answer.dto.ConfirmRequest;
import com.talkdoc.backend.answer.dto.PreviewRequest;
import com.talkdoc.backend.answer.dto.PreviewResponse;
import com.talkdoc.backend.answer.dto.UpdateAnswerRequest;
import com.talkdoc.backend.auth.RequireRole;
import com.talkdoc.backend.auth.Role;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Confirmed-answer lifecycle: preview a composed sentence, confirm it, edit it after the fact,
 * and synthesize it as speech for the doctor.
 */
@RestController
@RequestMapping("/api/sessions/{sessionId}/answer")
@Tag(name = "Answer", description = "환자 답변 미리보기/확정/수정 및 TTS")
public class AnswerController {

    private final AnswerService answerService;

    public AnswerController(AnswerService answerService) {
        this.answerService = answerService;
    }

    @PostMapping("/preview")
    @RequireRole(Role.PATIENT)
    @Operation(
            summary = "답변 미리보기",
            description = "인식된 라벨로 답변 문장을 미리 생성합니다. 저장하지 않습니다. patient_token 필요."
    )
    public PreviewResponse preview(@PathVariable String sessionId, @Valid @RequestBody PreviewRequest request) {
        return answerService.preview(sessionId, request.labels());
    }

    @PostMapping("/confirm")
    @RequireRole(Role.PATIENT)
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(
            summary = "답변 확정",
            description = "라벨(및 선택적으로 answer 문장)로 답변을 확정하고 대기 중인 질문을 해제합니다. " +
                    "answer가 있으면 labels는 비워도 됩니다(텍스트 답변). patient_token 필요."
    )
    public Conversation confirm(@PathVariable String sessionId, @Valid @RequestBody ConfirmRequest request) {
        return answerService.confirm(sessionId, request.labelsOrEmpty(), request.answer());
    }

    @PatchMapping("/{answerId}")
    @RequireRole({Role.DOCTOR, Role.PATIENT})
    @Operation(
            summary = "확정된 답변 수정",
            description = "이미 확정된 답변의 텍스트를 수정합니다. doctor_token 또는 patient_token 필요."
    )
    public Conversation update(@PathVariable String sessionId,
                                @PathVariable String answerId,
                                @Valid @RequestBody UpdateAnswerRequest request) {
        return answerService.updateAnswer(sessionId, answerId, request.answer());
    }

    @GetMapping("/{answerId}/tts")
    @RequireRole(Role.DOCTOR)
    @Operation(
            summary = "답변 음성 합성",
            description = "확정된 답변을 음성으로 합성해 반환합니다. doctor_token 필요."
    )
    public ResponseEntity<byte[]> tts(@PathVariable String sessionId, @PathVariable String answerId) {
        TtsAudio audio = answerService.synthesize(sessionId, answerId);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(audio.mimeType()))
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.inline().filename("answer.wav").build().toString())
                .body(audio.data());
    }
}

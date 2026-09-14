package com.talkdoc.backend.answer;

import com.talkdoc.backend.ai.model.TtsAudio;
import com.talkdoc.backend.answer.dto.ConfirmRequest;
import com.talkdoc.backend.answer.dto.PreviewRequest;
import com.talkdoc.backend.answer.dto.PreviewResponse;
import com.talkdoc.backend.answer.dto.UpdateAnswerRequest;
import com.talkdoc.backend.auth.AuthenticatedPrincipal;
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
import org.springframework.web.bind.annotation.RestController;

/**
 * Confirmed-answer lifecycle: preview a composed sentence (stored as a draft), confirm it, edit it
 * after the fact, and synthesize it as speech for the doctor.
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
            description = """
                    인식된 라벨로 답변 문장을 만들고 답변 초안(draft)으로 저장합니다.
                    body는 {"labels": [...]} (기존 방식) 또는
                    {"question_id": "...", "question_version": 2, "recognition_ids": [...]} 입니다.
                    labels / recognition_ids 중 최소 하나는 비어 있지 않아야 하며, 둘 다 있으면 recognition_ids가 우선합니다.
                    recognition_ids는 POST /sign 응답의 recognition_id를 보낸 순서대로 라벨로 바꿉니다.
                    question_id/question_version을 보내면 현재 대기 중인 질문·버전과 일치하는지 검사합니다
                    (불일치 시 404 QUESTION_NOT_FOUND / 409 VERSION_CONFLICT).
                    응답의 answer_id와 version을 /answer/confirm에 그대로 보내면 이 초안을 확정합니다. patient_token 필요."""
    )
    public PreviewResponse preview(@PathVariable String sessionId, @Valid @RequestBody PreviewRequest request) {
        return answerService.preview(sessionId, request);
    }

    @PostMapping("/confirm")
    @RequireRole(Role.PATIENT)
    @Operation(
            summary = "답변 확정",
            description = """
                    답변을 확정하고 대기 중인 질문을 해제합니다.
                    body는 {"labels": [...], "answer": "..."} (기존 방식, answer가 있으면 labels는 비워도 됨) 또는
                    {"answer_id": "...", "version": 1} (미리보기로 만든 초안 확정) 입니다.
                    초안 확정은 멱등합니다: 같은 answer_id로 다시 호출하면 이미 확정된 답변을 200으로 돌려주고
                    대화를 다시 추가하지 않습니다. 새로 확정하면 201 입니다.
                    초안을 만든 뒤 의사가 질문을 수정했다면 409 DRAFT_INVALIDATED,
                    version이 초안 버전과 다르면 409 VERSION_CONFLICT,
                    초안이 없으면 404 DRAFT_NOT_FOUND 입니다. patient_token 필요."""
    )
    public ResponseEntity<Conversation> confirm(@PathVariable String sessionId,
                                                 @Valid @RequestBody ConfirmRequest request) {
        AnswerService.ConfirmResult result = answerService.confirm(sessionId, request);
        return ResponseEntity.status(result.created() ? HttpStatus.CREATED : HttpStatus.OK)
                .body(result.conversation());
    }

    @PatchMapping("/{answerId}")
    @RequireRole({Role.DOCTOR, Role.PATIENT})
    @Operation(
            summary = "확정된 답변 수정",
            description = """
                    이미 확정된 답변의 텍스트를 수정합니다. body는 {"answer": "...", "version": 1} 이며
                    version(선택)이 현재 답변 버전과 다르면 409 VERSION_CONFLICT 입니다.
                    doctor_token으로 호출하면 바로 반영하지 않고 수정 초안(pending_edit)만 만들며
                    기존 확정본(answer)과 version은 그대로 유지됩니다 (WebSocket ANSWER_EDIT_PROPOSED).
                    patient_token으로 호출하면 실제로 반영되어 answer가 바뀌고 version이 1 증가하며
                    pending_edit이 지워집니다 (WebSocket ANSWER_UPDATED)."""
    )
    public Conversation update(@PathVariable String sessionId,
                                @PathVariable String answerId,
                                @Valid @RequestBody UpdateAnswerRequest request,
                                AuthenticatedPrincipal principal) {
        return answerService.updateAnswer(sessionId, answerId, request.answer(), request.version(), principal.role());
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

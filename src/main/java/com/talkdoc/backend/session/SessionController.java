package com.talkdoc.backend.session;

import com.talkdoc.backend.auth.AuthenticatedPrincipal;
import com.talkdoc.backend.auth.RequireRole;
import com.talkdoc.backend.auth.Role;
import com.talkdoc.backend.session.dto.CreateSessionResponse;
import com.talkdoc.backend.session.dto.SessionDetailResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Session lifecycle endpoints: create (anonymous, no auth), detail and delete (doctor-only).
 */
@RestController
@RequestMapping("/api/sessions")
@Tag(name = "Session", description = "익명 문진 세션 생성/조회/종료")
public class SessionController {

    private final SessionService sessionService;

    public SessionController(SessionService sessionService) {
        this.sessionService = sessionService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(
            summary = "세션 생성",
            description = "의사가 새 익명 문진 세션을 생성합니다. 인증이 필요 없으며, 발급된 doctor_token/patient_token은 이후 요청에 사용됩니다."
    )
    public CreateSessionResponse create() {
        SessionService.CreatedSession created = sessionService.create();
        return CreateSessionResponse.of(created.session(), created.tokens());
    }

    @GetMapping("/{sessionId}")
    @RequireRole({Role.DOCTOR, Role.PATIENT})
    @Operation(
            summary = "세션 상세 조회",
            description = """
                    세션 상태, 현재 대기 중인 질문(question_version 포함), 확정된 대화 목록을 반환합니다.
                    확정된 대화에는 의사가 제안한 수정 초안(pending_edit)도 함께 들어갑니다.
                    patient_token으로 조회하면 현재 질문에 대한 자신의 답변 초안(drafts, status=DRAFT)과
                    수어 인식 기록(recognitions)이 추가로 내려갑니다.
                    doctor_token으로 조회하면 drafts/recognitions는 내려가지 않습니다
                    (확정 전 답변은 의사에게 노출하지 않음). doctor_token 또는 patient_token 필요."""
    )
    public SessionDetailResponse detail(@PathVariable String sessionId, AuthenticatedPrincipal principal) {
        return sessionService.detail(sessionId, principal.role());
    }

    @DeleteMapping("/{sessionId}")
    @RequireRole(Role.DOCTOR)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(
            summary = "세션 종료",
            description = "세션과 관련된 모든 Redis 데이터를 삭제하고 연결된 WebSocket 세션을 종료합니다. doctor_token 필요. 이미 삭제된 세션에 대해서도 204를 반환합니다."
    )
    public void delete(@PathVariable String sessionId) {
        sessionService.delete(sessionId);
    }
}

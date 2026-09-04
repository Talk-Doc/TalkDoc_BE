package com.talkdoc.backend.session;

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
    @RequireRole(Role.DOCTOR)
    @Operation(
            summary = "세션 상세 조회",
            description = "세션 상태, 현재 대기 중인 질문, 확정된 대화 목록을 반환합니다. doctor_token 필요."
    )
    public SessionDetailResponse detail(@PathVariable String sessionId) {
        return sessionService.detail(sessionId);
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

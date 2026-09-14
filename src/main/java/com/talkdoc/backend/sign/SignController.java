package com.talkdoc.backend.sign;

import com.talkdoc.backend.auth.RequireRole;
import com.talkdoc.backend.auth.Role;
import com.talkdoc.backend.sign.dto.SignResponse;
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
 * Patient sign-video recognition against the current (or an explicitly given) question intent.
 */
@RestController
@RequestMapping("/api/sessions/{sessionId}/sign")
@Tag(name = "Sign", description = "환자 수어 영상 인식")
public class SignController {

    private final SignService signService;

    public SignController(SignService signService) {
        this.signService = signService;
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @RequireRole(Role.PATIENT)
    @Operation(
            summary = "수어 영상 인식",
            description = """
                    환자가 촬영한 수어 영상(video/webm 또는 video/mp4) 1개를 업로드해 단어 1개를 인식합니다.
                    여러 단어를 모으려면 단어마다 한 번씩 호출하세요.
                    duration(선택)은 실제 녹화 길이(초)이며 0 초과 20 이하여야 합니다.
                    intent를 생략하면 현재 대기 중인 질문의 의도를 사용합니다.
                    candidates는 참고용이며 인식 결과를 제한하지 않습니다.
                    accepted=false 이면 재촬영이 필요하고 reason으로 이유를 알려줍니다:
                    LOW_CONFIDENCE(신뢰도 부족, 다시 촬영),
                    INSUFFICIENT_LANDMARKS(손·상반신이 보이지 않음, 손과 상반신이 보이도록 다시 촬영).
                    영상을 해석할 수 없으면 422(SIGN_VIDEO_REJECTED)로 실패합니다.
                    아무것도 저장하지 않습니다. patient_token 필요."""
    )
    public SignResponse post(@PathVariable String sessionId,
                              @RequestPart("video") MultipartFile video,
                              @RequestParam(value = "intent", required = false) String intent,
                              @RequestParam(value = "duration", required = false) Double duration) {
        return signService.recognize(sessionId, video, intent, duration);
    }
}

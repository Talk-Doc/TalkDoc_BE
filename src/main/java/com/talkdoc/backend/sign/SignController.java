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
            description = "환자가 촬영한 수어 영상을 업로드해 라벨을 인식합니다. intent를 생략하면 현재 대기 중인 " +
                    "질문의 의도를 사용합니다. 아무것도 저장하지 않습니다. patient_token 필요."
    )
    public SignResponse post(@PathVariable String sessionId,
                              @RequestPart("video") MultipartFile video,
                              @RequestParam(value = "intent", required = false) String intent) {
        return signService.recognize(sessionId, video, intent);
    }
}

package com.talkdoc.backend.summary;

import com.talkdoc.backend.auth.RequireRole;
import com.talkdoc.backend.auth.Role;
import com.talkdoc.backend.summary.dto.SummaryResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Doctor-facing summary of the confirmed conversation. Produces a statement-only recap of what
 * the patient signed — never a diagnosis, cause, or treatment suggestion; the final medical
 * judgement always belongs to the doctor.
 */
@RestController
@RequestMapping("/api/sessions/{sessionId}/summary")
@Tag(name = "Summary", description = "진료 요약 생성 (진단 아님, 진술 요약)")
public class SummaryController {

    private final SummaryService summaryService;

    public SummaryController(SummaryService summaryService) {
        this.summaryService = summaryService;
    }

    @PostMapping
    @RequireRole(Role.DOCTOR)
    @Operation(
            summary = "진료 요약 생성",
            description = "세션에서 확정된 모든 답변을 바탕으로 한 문장 요약을 생성합니다. 확정된 답변이 없으면 " +
                    "LLM을 호출하지 않고 빈 요약을 반환합니다. doctor_token 필요."
    )
    public SummaryResponse post(@PathVariable String sessionId) {
        return summaryService.summarize(sessionId);
    }
}

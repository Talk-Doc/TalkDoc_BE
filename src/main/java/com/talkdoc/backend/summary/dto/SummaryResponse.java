package com.talkdoc.backend.summary.dto;

import java.time.Instant;

/**
 * Response body for {@code POST /api/sessions/{sessionId}/summary}. Serialized snake_case:
 * {@code summary, conversation_count, generated_at}. Not stored.
 */
public record SummaryResponse(
        String summary,
        int conversationCount,
        Instant generatedAt
) {
}

package com.talkdoc.backend.summary;

import com.talkdoc.backend.ai.LlmClient;
import com.talkdoc.backend.answer.Conversation;
import com.talkdoc.backend.answer.ConversationRepository;
import com.talkdoc.backend.session.SessionService;
import com.talkdoc.backend.summary.dto.SummaryResponse;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;

/**
 * Builds a one-sentence, statement-only summary of the session's confirmed conversations.
 * No diagnosis or medical judgement is generated here; that constraint is enforced by the
 * {@link LlmClient} adapter's prompts (see resources/prompts).
 */
@Service
public class SummaryService {

    private final SessionService sessionService;
    private final ConversationRepository conversationRepository;
    private final LlmClient llmClient;

    public SummaryService(SessionService sessionService,
                           ConversationRepository conversationRepository,
                           LlmClient llmClient) {
        this.sessionService = sessionService;
        this.conversationRepository = conversationRepository;
        this.llmClient = llmClient;
    }

    public SummaryResponse summarize(String sessionId) {
        sessionService.requireActive(sessionId);

        List<Conversation> conversations = conversationRepository.findAll(sessionId);
        if (conversations.isEmpty()) {
            return new SummaryResponse("", 0, Instant.now());
        }

        String summary = llmClient.summarize(conversations);
        return new SummaryResponse(summary, conversations.size(), Instant.now());
    }
}

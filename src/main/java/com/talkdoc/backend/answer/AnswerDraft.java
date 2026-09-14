package com.talkdoc.backend.answer;

import com.fasterxml.jackson.annotation.JsonIgnore;

import java.time.Instant;
import java.util.List;

/**
 * A composed but not yet confirmed answer, created by {@code POST /answer/preview} and stored in the
 * drafts hash so that {@code POST /answer/confirm} can confirm it by id (and do so idempotently).
 *
 * <p>A draft is bound to one {@code questionId} + {@code questionVersion}: when the doctor edits the
 * question the draft becomes {@link DraftStatus#INVALIDATED} and the patient must preview again.</p>
 *
 * @param labels         the labels the answer was composed from, in signing order
 * @param recognitionIds the recognitions those labels came from, or null when the client sent raw labels
 * @param version        1 today; reserved for re-previewing into the same draft (older JSON reads back as 1)
 */
public record AnswerDraft(
        String answerId,
        String questionId,
        int questionVersion,
        List<String> labels,
        List<String> recognitionIds,
        String answer,
        int version,
        DraftStatus status,
        Instant createdAt,
        Instant updatedAt
) {

    public AnswerDraft {
        if (questionVersion <= 0) {
            questionVersion = 1;
        }
        if (version <= 0) {
            version = 1;
        }
        if (status == null) {
            status = DraftStatus.DRAFT;
        }
    }

    /** True while the draft can still be confirmed. Derived, so it is not part of the stored JSON. */
    @JsonIgnore
    public boolean isDraft() {
        return status == DraftStatus.DRAFT;
    }

    public AnswerDraft withStatus(DraftStatus newStatus, Instant at) {
        return new AnswerDraft(answerId, questionId, questionVersion, labels, recognitionIds, answer,
                version, newStatus, createdAt, at);
    }
}

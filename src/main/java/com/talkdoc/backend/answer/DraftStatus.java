package com.talkdoc.backend.answer;

/** Lifecycle of an {@link AnswerDraft} created by {@code POST /answer/preview}. */
public enum DraftStatus {
    /** Created by a preview, still confirmable. */
    DRAFT,
    /** Confirmed into a {@link Conversation}; confirming it again is idempotent. */
    CONFIRMED,
    /**
     * No longer confirmable: the question was edited (new version) or another draft for the same
     * question was confirmed. The record is kept so clients can tell "gone" from "stale".
     */
    INVALIDATED
}

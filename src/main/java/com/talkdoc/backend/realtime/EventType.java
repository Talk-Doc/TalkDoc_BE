package com.talkdoc.backend.realtime;

public enum EventType {
    /** Doctor posted a question; payload = PendingQuestion. Delivered to the patient (and doctor). */
    QUESTION_POSTED,
    /**
     * Doctor edited the pending question (PATCH /questions/{questionId}); payload = PendingQuestion
     * with the same question_id and an incremented version. Drafts made for the older version are
     * invalidated, so the patient must preview again.
     */
    QUESTION_UPDATED,
    /** Patient confirmed an answer; payload = Conversation. Delivered to the doctor (and patient). */
    ANSWER_CONFIRMED,
    /**
     * Doctor proposed an edit to a confirmed answer; payload = Conversation whose {@code pending_edit}
     * carries the proposal. The confirmed {@code answer} is unchanged until the patient re-confirms it
     * with PATCH /answer/{answerId}.
     */
    ANSWER_EDIT_PROPOSED,
    /** A confirmed answer text was edited by the patient; payload = Conversation. */
    ANSWER_UPDATED,
    /** Session deleted; payload = {"session_id": ...}. Connections are closed afterwards. */
    SESSION_CLOSED,
    /** The other role connected; payload = {"role": "DOCTOR"|"PATIENT"}. */
    PEER_JOINED
}

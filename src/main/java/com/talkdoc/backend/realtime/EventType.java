package com.talkdoc.backend.realtime;

public enum EventType {
    /** Doctor posted a question; payload = PendingQuestion. Delivered to the patient (and doctor). */
    QUESTION_POSTED,
    /** Patient confirmed an answer; payload = Conversation. Delivered to the doctor (and patient). */
    ANSWER_CONFIRMED,
    /** A confirmed answer text was edited; payload = Conversation. */
    ANSWER_UPDATED,
    /** Session deleted; payload = {"session_id": ...}. Connections are closed afterwards. */
    SESSION_CLOSED,
    /** The other role connected; payload = {"role": "DOCTOR"|"PATIENT"}. */
    PEER_JOINED
}

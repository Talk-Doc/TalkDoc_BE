package com.talkdoc.backend.answer;

import com.talkdoc.backend.auth.Role;

import java.time.Instant;

/**
 * A proposed change to an already-confirmed answer that has not been applied yet.
 *
 * <p>The doctor may not silently rewrite what the patient said: a doctor PATCH on a confirmed answer
 * stores the proposal here and leaves {@link Conversation#answer()} untouched. The patient applies it
 * by sending the same PATCH, which clears this field and bumps the conversation version.</p>
 *
 * @param answer     the proposed replacement sentence
 * @param proposedBy the role that proposed it (always DOCTOR today)
 */
public record PendingEdit(String answer, Role proposedBy, Instant proposedAt) {
}

package com.talkdoc.backend.question;

import java.util.List;

/**
 * Decides how a question should be answered (sign vs. card) and, for card answers, which options
 * to offer. Implementations: {@link StaticAnswerModeResolver} (fixed per-Intent lookup, default).
 * A future LLM-driven implementation could take questionText into account per-question rather
 * than per-Intent; that's why it's part of the interface even though the static resolver ignores it.
 */
public interface AnswerModeResolver {

    AnswerMode resolve(Intent intent, String questionText);

    /** Empty when {@link #resolve} returns {@link AnswerMode#SIGN_REQUIRED}. */
    List<String> cardOptions(Intent intent, String questionText);
}

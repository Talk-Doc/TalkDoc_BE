package com.talkdoc.backend.question;

/** How the patient should answer a question. */
public enum AnswerMode {
    /** Answer has too many possible values to enumerate; the patient signs it (e.g. "어디가 아프세요?"). */
    SIGN_REQUIRED,
    /** Answer is one of a small fixed set; the frontend shows selectable cards (e.g. "언제부터 아팠어요?"). */
    CARD_SELECT
}

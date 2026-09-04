package com.talkdoc.backend.common;

import java.time.Instant;

/** Uniform error body: {"code":"SESSION_NOT_FOUND","message":"...","timestamp":"..."} */
public record ErrorResponse(String code, String message, Instant timestamp) {

    public static ErrorResponse of(ErrorCode code, String message) {
        return new ErrorResponse(code.name(), message, Instant.now());
    }
}

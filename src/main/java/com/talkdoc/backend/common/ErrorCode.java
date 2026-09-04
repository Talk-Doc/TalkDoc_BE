package com.talkdoc.backend.common;

import org.springframework.http.HttpStatus;

public enum ErrorCode {
    INVALID_REQUEST(HttpStatus.BAD_REQUEST, "요청 형식이 올바르지 않습니다."),
    UNAUTHORIZED(HttpStatus.UNAUTHORIZED, "세션 토큰이 없거나 유효하지 않습니다."),
    FORBIDDEN(HttpStatus.FORBIDDEN, "이 세션 또는 역할로는 접근할 수 없습니다."),
    SESSION_NOT_FOUND(HttpStatus.NOT_FOUND, "세션을 찾을 수 없습니다."),
    ANSWER_NOT_FOUND(HttpStatus.NOT_FOUND, "답변을 찾을 수 없습니다."),
    NO_PENDING_QUESTION(HttpStatus.CONFLICT, "현재 답변 대기 중인 질문이 없습니다."),
    SESSION_CLOSED(HttpStatus.CONFLICT, "이미 종료된 세션입니다."),
    FILE_TOO_LARGE(HttpStatus.PAYLOAD_TOO_LARGE, "업로드 파일이 너무 큽니다."),
    UNSUPPORTED_MEDIA(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "지원하지 않는 파일 형식입니다."),
    STT_FAILED(HttpStatus.BAD_GATEWAY, "음성 인식에 실패했습니다."),
    LLM_FAILED(HttpStatus.BAD_GATEWAY, "언어 모델 호출에 실패했습니다."),
    SIGN_AI_FAILED(HttpStatus.BAD_GATEWAY, "수어 인식 서비스 호출에 실패했습니다."),
    TTS_FAILED(HttpStatus.BAD_GATEWAY, "음성 합성에 실패했습니다."),
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "서버 내부 오류가 발생했습니다.");

    private final HttpStatus status;
    private final String defaultMessage;

    ErrorCode(HttpStatus status, String defaultMessage) {
        this.status = status;
        this.defaultMessage = defaultMessage;
    }

    public HttpStatus status() {
        return status;
    }

    public String defaultMessage() {
        return defaultMessage;
    }
}

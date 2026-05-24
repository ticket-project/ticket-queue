package com.ticket.queue.domain.support.exception;

public enum ErrorType {
    DEFAULT_ERROR(ErrorCode.E500, "Unexpected server error"),
    INVALID_REQUEST(ErrorCode.E400, "Invalid request"),
    AUTHORIZATION_ERROR(ErrorCode.E403, "Forbidden"),
    LOCK_ACQUISITION_FAILED(ErrorCode.E409, "Failed to acquire distributed lock");

    private final ErrorCode code;
    private final String message;

    ErrorType(final ErrorCode code, final String message) {
        this.code = code;
        this.message = message;
    }

    public ErrorCode getCode() {
        return code;
    }

    public String getMessage() {
        return message;
    }
}
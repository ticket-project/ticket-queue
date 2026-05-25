package com.ticket.queue.infra;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum ErrorType {
    DEFAULT_ERROR(ErrorCode.E500, "Unexpected server error"),
    INVALID_REQUEST(ErrorCode.E400, "Invalid request"),
    LOCK_ACQUISITION_FAILED(ErrorCode.E409, "Failed to acquire distributed lock");

    private final ErrorCode code;
    private final String message;
}

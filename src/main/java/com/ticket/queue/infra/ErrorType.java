package com.ticket.queue.infra;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum ErrorType {
    LOCK_ACQUISITION_FAILED(ErrorCode.E409, "Failed to acquire distributed lock");

    private final ErrorCode code;
    private final String message;
}

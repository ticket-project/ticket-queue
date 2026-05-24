package com.ticket.queue.domain.support.exception;

public class CoreException extends RuntimeException {

    private final ErrorType errorType;

    public CoreException(final ErrorType errorType) {
        this(errorType, errorType.getMessage());
    }

    public CoreException(final ErrorType errorType, final String message) {
        super(message);
        this.errorType = errorType;
    }

    public CoreException(final ErrorType errorType, final String message, final Throwable cause) {
        super(message, cause);
        this.errorType = errorType;
    }

    public ErrorType getErrorType() {
        return errorType;
    }
}
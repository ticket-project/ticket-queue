package com.ticket.queue.domain.support.exception;

public class AuthException extends CoreException {

    public AuthException(final String message) {
        super(ErrorType.AUTHORIZATION_ERROR, message);
    }
}
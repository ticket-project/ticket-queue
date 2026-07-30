package com.ticket.queue.application;

public class QueueTokenException extends RuntimeException {

    public QueueTokenException(final String message) {
        super(message);
    }

    public QueueTokenException(final String message, final Throwable cause) {
        super(message, cause);
    }
}

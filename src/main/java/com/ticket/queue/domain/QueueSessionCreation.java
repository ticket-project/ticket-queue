package com.ticket.queue.domain;

import java.util.Objects;

public record QueueSessionCreation(
        QueueSession session,
        boolean created
) {

    public QueueSessionCreation {
        Objects.requireNonNull(session, "session must not be null");
    }
}

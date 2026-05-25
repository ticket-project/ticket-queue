package com.ticket.queue.domain;

import java.time.Duration;

public record QueueTicket(
        Long performanceId,
        String queueSessionId,
        QueueEntryStatus status,
        Long sequence,
        Duration activeTtl
) {

    public QueueTicket(
            final Long performanceId,
            final String queueSessionId,
            final QueueEntryStatus status,
            final Long sequence
    ) {
        this(performanceId, queueSessionId, status, sequence, null);
    }

    public QueueTicket {
        validatePositive(performanceId, "performanceId");
        if (queueSessionId == null || queueSessionId.isBlank()) {
            throw new IllegalArgumentException("queueSessionId must not be blank");
        }
        if (status == null) {
            throw new IllegalArgumentException("status must not be null");
        }
        if (status == QueueEntryStatus.ADMITTED
                && (activeTtl == null || activeTtl.isZero() || activeTtl.isNegative())) {
            throw new IllegalArgumentException("activeTtl must be positive for admitted ticket");
        }
    }

    public boolean isWaiting() {
        return status == QueueEntryStatus.WAITING;
    }

    public boolean isAdmitted() {
        return status == QueueEntryStatus.ADMITTED;
    }

    private static void validatePositive(final Long value, final String name) {
        if (value == null || value <= 0) {
            throw new IllegalArgumentException(name + " must be positive");
        }
    }
}

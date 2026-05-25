package com.ticket.queue.domain;

public record QueueSession(
        String queueSessionId,
        Long performanceId
) {

    public QueueSession {
        if (queueSessionId == null || queueSessionId.isBlank()) {
            throw new IllegalArgumentException("queueSessionId must not be blank");
        }
        validatePositive(performanceId, "performanceId");
    }

    private static void validatePositive(final Long value, final String name) {
        if (value == null || value <= 0) {
            throw new IllegalArgumentException(name + " must be positive");
        }
    }
}

package com.ticket.queue.domain.model;

public record QueueSession(
        String queueSessionId,
        Long performanceId,
        Long memberId
) {

    public QueueSession {
        if (queueSessionId == null || queueSessionId.isBlank()) {
            throw new IllegalArgumentException("queueSessionId must not be blank");
        }
        validatePositive(performanceId, "performanceId");
        validatePositive(memberId, "memberId");
    }

    private static void validatePositive(final Long value, final String name) {
        if (value == null || value <= 0) {
            throw new IllegalArgumentException(name + " must be positive");
        }
    }
}
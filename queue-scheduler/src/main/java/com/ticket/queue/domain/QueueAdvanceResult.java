package com.ticket.queue.domain;

public record QueueAdvanceResult(
        boolean observed,
        int admittedCount,
        long backlog,
        long oldestPendingSlotAgeMillis
) {

    public QueueAdvanceResult {
        if (admittedCount < 0 || backlog < 0 || oldestPendingSlotAgeMillis < 0) {
            throw new IllegalArgumentException("queue advance metrics must not be negative");
        }
        if (!observed && (admittedCount != 0 || backlog != 0 || oldestPendingSlotAgeMillis != 0)) {
            throw new IllegalArgumentException("unobserved queue advance result must be empty");
        }
    }

    public static QueueAdvanceResult observed(
            final int admittedCount,
            final long backlog,
            final long oldestPendingSlotAgeMillis
    ) {
        return new QueueAdvanceResult(true, admittedCount, backlog, oldestPendingSlotAgeMillis);
    }

    public static QueueAdvanceResult skipped() {
        return new QueueAdvanceResult(false, 0, 0, 0);
    }
}

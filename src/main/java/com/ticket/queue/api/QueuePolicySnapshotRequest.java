package com.ticket.queue.api;

import com.ticket.queue.domain.QueueMode;
import jakarta.validation.constraints.Positive;
import java.time.Duration;
import java.time.LocalDateTime;

public record QueuePolicySnapshotRequest(
        @Positive int admitLimitPerTick,
        @Positive int maxActiveUsers,
        @Positive long activeTtlSeconds,
        @Positive long sessionTtlSeconds,
        QueueMode queueMode,
        LocalDateTime preopenQueueStartAt,
        LocalDateTime orderCloseTime
) {

    Duration activeTtl() {
        return Duration.ofSeconds(activeTtlSeconds);
    }

    Duration sessionTtl() {
        return Duration.ofSeconds(sessionTtlSeconds);
    }

    QueueMode resolvedQueueMode() {
        return queueMode == null ? QueueMode.FORCE_ON : queueMode;
    }
}

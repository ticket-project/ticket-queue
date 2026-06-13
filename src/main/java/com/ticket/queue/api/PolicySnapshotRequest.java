package com.ticket.queue.api;

import com.ticket.queue.domain.PolicyMode;
import jakarta.validation.constraints.Positive;
import java.time.Duration;
import java.time.LocalDateTime;

public record PolicySnapshotRequest(
        @Positive int admitLimitPerTick,
        @Positive int maxActiveUsers,
        @Positive long activeTtlSeconds,
        @Positive long sessionTtlSeconds,
        PolicyMode queueMode,
        LocalDateTime preopenQueueStartAt,
        LocalDateTime orderCloseTime
) {

    Duration activeTtl() {
        return Duration.ofSeconds(activeTtlSeconds);
    }

    Duration sessionTtl() {
        return Duration.ofSeconds(sessionTtlSeconds);
    }

    PolicyMode resolvedPolicyMode() {
        return queueMode == null ? PolicyMode.FORCE_ON : queueMode;
    }
}

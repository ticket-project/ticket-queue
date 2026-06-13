package com.ticket.queue.domain;

import java.time.Duration;
import java.time.LocalDateTime;

public record Policy(
        int admitLimitPerTick,
        int maxActiveUsers,
        Duration activeTtl,
        Duration sessionTtl,
        PolicyMode queueMode,
        LocalDateTime preopenQueueStartAt,
        LocalDateTime orderCloseTime
) {

    public Policy(
            final int admitLimitPerTick,
            final int maxActiveUsers,
            final Duration activeTtl,
            final Duration sessionTtl
    ) {
        this(admitLimitPerTick, maxActiveUsers, activeTtl, sessionTtl, PolicyMode.FORCE_ON, null, null);
    }

    public Policy {
        if (admitLimitPerTick <= 0) {
            throw new IllegalArgumentException("admitLimitPerTick must be positive");
        }
        if (maxActiveUsers <= 0) {
            throw new IllegalArgumentException("maxActiveUsers must be positive");
        }
        if (activeTtl == null || activeTtl.isZero() || activeTtl.isNegative()) {
            throw new IllegalArgumentException("activeTtl must be positive");
        }
        if (sessionTtl == null || sessionTtl.isZero() || sessionTtl.isNegative()) {
            throw new IllegalArgumentException("sessionTtl must be positive");
        }
        if (queueMode == null) {
            queueMode = PolicyMode.FORCE_ON;
        }
    }

    public long estimateWaitSeconds(final long position) {
        if (position <= 0) {
            return 0L;
        }
        return Math.max(1L, (long) Math.ceil((double) position / admitLimitPerTick));
    }

    public boolean requiresQueueAt(final LocalDateTime now) {
        if (queueMode == PolicyMode.FORCE_OFF) {
            return false;
        }
        if (queueMode == PolicyMode.FORCE_ON) {
            return true;
        }
        if (preopenQueueStartAt == null || now == null || now.isBefore(preopenQueueStartAt)) {
            return false;
        }
        return orderCloseTime == null || !now.isAfter(orderCloseTime);
    }
}

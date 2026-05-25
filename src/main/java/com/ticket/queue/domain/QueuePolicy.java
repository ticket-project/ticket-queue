package com.ticket.queue.domain;

import java.time.Duration;

public record QueuePolicy(
        int admitLimitPerTick,
        Duration activeTtl
) {

    public QueuePolicy {
        if (admitLimitPerTick <= 0) {
            throw new IllegalArgumentException("admitLimitPerTick must be positive");
        }
        if (activeTtl == null || activeTtl.isZero() || activeTtl.isNegative()) {
            throw new IllegalArgumentException("activeTtl must be positive");
        }
    }

    public long estimateWaitSeconds(final long position) {
        if (position <= 0) {
            return 0L;
        }
        return Math.max(1L, (long) Math.ceil((double) position / admitLimitPerTick));
    }
}

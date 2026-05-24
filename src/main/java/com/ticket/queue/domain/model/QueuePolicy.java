package com.ticket.queue.domain.model;

import java.time.Duration;

public record QueuePolicy(
        QueueLevel queueLevel,
        int maxActiveUsers,
        Duration entryTokenTtl,
        Duration entryRetention
) {

    public QueuePolicy {
        if (queueLevel == null) {
            throw new IllegalArgumentException("queueLevel must not be null");
        }
        if (maxActiveUsers <= 0) {
            throw new IllegalArgumentException("maxActiveUsers must be positive");
        }
        if (entryTokenTtl == null || entryTokenTtl.isZero() || entryTokenTtl.isNegative()) {
            throw new IllegalArgumentException("entryTokenTtl must be positive");
        }
        if (entryRetention == null || entryRetention.isZero() || entryRetention.isNegative()) {
            throw new IllegalArgumentException("entryRetention must be positive");
        }
    }

    public long estimateWaitSeconds(final long position) {
        if (position <= 0) {
            return 0L;
        }
        return Math.max(1L, (long) Math.ceil((double) position / maxActiveUsers));
    }
}
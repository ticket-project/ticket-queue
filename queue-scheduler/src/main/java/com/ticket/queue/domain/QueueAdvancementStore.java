package com.ticket.queue.domain;

import java.time.Duration;
import java.util.Set;

public interface QueueAdvancementStore {

    Set<Long> findWaitingPerformanceIds();

    void advancePublicState(
            Long performanceId,
            int maxAdmitPerSecond,
            int maxActiveSessions,
            int shardCount,
            long slotSizeMillis,
            long slotCloseGraceMillis,
            Duration stateTtl,
            long refreshAfterMs
    );
}

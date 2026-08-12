package com.ticket.queue.domain;

import java.time.Duration;
import java.util.Set;

public interface QueueAdvancementStore {

    Set<Long> findWaitingPerformanceIds();

    QueueAdvanceResult advancePublicState(
            Long performanceId,
            int advanceBatchSize,
            int shardCount,
            long slotSizeMillis,
            long slotCloseGraceMillis,
            Duration stateTtl,
            long refreshAfterMs
    );
}

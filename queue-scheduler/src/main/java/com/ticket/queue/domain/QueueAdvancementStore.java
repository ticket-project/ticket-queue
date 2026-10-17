package com.ticket.queue.domain;

import java.time.Duration;
import java.util.Optional;
import java.util.Set;

public interface QueueAdvancementStore {

    Set<Long> findWaitingPerformanceIds();

    Optional<String> findAdvanceBatchSizeOverride();

    int advancePublicState(
            Long performanceId,
            int advanceBatchSize,
            int shardCount,
            long slotSizeMillis,
            long slotCloseGraceMillis,
            Duration stateTtl,
            long refreshAfterMs
    );
}

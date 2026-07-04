package com.ticket.queue.domain;

import java.time.Duration;
import java.util.Set;

public interface AdmissionStateStore {

    Set<Long> findWaitingPerformanceIds();

    JoinResult joinQueue(
            Long performanceId,
            String userIdHash,
            String candidateQueueId,
            QueueShardSlot shardSlot,
            Duration queueTtl
    );

    PublicState readPublicState(Long performanceId, long refreshAfterMs);

    EnterResult enterQueue(
            Long performanceId,
            String queueId,
            int shardId,
            Long localSeq,
            String admissionToken,
            Duration shoppingSessionTtl,
            int maxActiveSessions
    );

    EnterResult enterLegacyQueue(
            Long performanceId,
            String queueId,
            Long seq,
            String admissionToken,
            Duration shoppingSessionTtl,
            int maxActiveSessions
    );

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

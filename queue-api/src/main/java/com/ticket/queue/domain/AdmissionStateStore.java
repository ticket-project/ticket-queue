package com.ticket.queue.domain;

import java.time.Duration;

public interface AdmissionStateStore {

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

}

package com.ticket.queue.domain;

import java.time.Duration;
import java.util.Set;

public interface AdmissionStateStore {

    Set<Long> findWaitingPerformanceIds();

    JoinResult joinQueue(
            Long performanceId,
            String userIdHash,
            String candidateQueueId,
            Duration queueTtl,
            long refreshAfterMs
    );

    PublicState readPublicState(Long performanceId, long refreshAfterMs);

    EnterResult enterQueue(
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
            int maxActiveSessions
    );
}

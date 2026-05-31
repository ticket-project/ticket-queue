package com.ticket.queue.domain;

import java.time.Duration;
import java.util.Optional;
import java.util.Set;

public interface QueueTicketStore {

    void registerWaiting(Long performanceId, String queueSessionId, Duration sessionTtl);

    QueueSessionCreation createSession(Long performanceId, Long memberId, Duration sessionTtl);

    Optional<QueueSession> findSession(String queueSessionId);

    Optional<Long> findWaitingPosition(Long performanceId, String queueSessionId);

    Optional<QueueTicket> findTicket(Long performanceId, String queueSessionId);

    Set<Long> findWaitingPerformanceIds();

    void admitWaitingBatch(
            Long performanceId,
            int limit,
            int maxActiveUsers,
            Duration activeTtl
    );
}

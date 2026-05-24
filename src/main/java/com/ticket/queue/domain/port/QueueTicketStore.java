package com.ticket.queue.domain.port;

import com.ticket.queue.domain.model.QueueSession;
import com.ticket.queue.domain.model.QueueTicket;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.Set;

public interface QueueTicketStore {

    long countActive(Long performanceId);

    long countWaiting(Long performanceId);

    QueueTicket registerWaiting(Long performanceId, Long memberId, Duration entryRetention);

    QueueSession createSession(Long performanceId, Long memberId, Duration sessionTtl);

    Optional<QueueSession> findSession(String queueSessionId);

    void deleteSession(String queueSessionId);

    Optional<Long> findWaitingPosition(Long performanceId, Long memberId);

    Optional<QueueTicket> findTicket(Long performanceId, Long memberId);

    Set<Long> findWaitingPerformanceIds();

    Optional<QueueTicket> admitNextWaiting(
            Long performanceId,
            Duration activeTtl,
            Duration entryRetention,
            LocalDateTime now
    );

    void leaveWaiting(Long performanceId, Long memberId);

    void leaveAdmitted(Long performanceId, Long memberId);
}
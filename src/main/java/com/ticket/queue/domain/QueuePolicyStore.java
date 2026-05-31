package com.ticket.queue.domain;

import java.util.Optional;

public interface QueuePolicyStore {

    void save(Long performanceId, QueuePolicy policy);

    Optional<QueuePolicy> findByPerformanceId(Long performanceId);
}

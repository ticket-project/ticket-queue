package com.ticket.queue.domain;

import java.util.Optional;

public interface PolicyStore {

    void save(Long performanceId, Policy policy);

    Optional<Policy> findByPerformanceId(Long performanceId);
}

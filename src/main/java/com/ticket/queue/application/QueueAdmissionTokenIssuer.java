package com.ticket.queue.application;

import java.time.Duration;

public interface QueueAdmissionTokenIssuer {

    String issue(Long performanceId, String queueSessionId, Duration ttl);
}

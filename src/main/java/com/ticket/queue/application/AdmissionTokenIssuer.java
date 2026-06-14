package com.ticket.queue.application;

import java.time.Duration;

public interface AdmissionTokenIssuer {

    String issue(Long performanceId, String queueId, Duration ttl);
}

package com.ticket.queue.application;

import java.time.Duration;

public interface QueueTokenService {

    String issue(QueueTokenClaims claims, Duration ttl);

    QueueTokenClaims verify(String token);
}

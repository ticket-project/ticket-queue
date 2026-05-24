package com.ticket.queue.domain.service;

import com.ticket.queue.domain.model.QueuePolicy;
import com.ticket.queue.domain.model.QueueProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class QueuePolicyResolver {

    private final QueueProperties queueProperties;

    public QueuePolicy resolve(final Long performanceId) {
        return new QueuePolicy(
                queueProperties.getDefaultLevel(),
                queueProperties.getDefaultMaxActiveUsers(),
                queueProperties.getDefaultEntryTokenTtl(),
                queueProperties.getEntryRetention()
        );
    }
}
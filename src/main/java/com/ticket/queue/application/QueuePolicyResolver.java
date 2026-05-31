package com.ticket.queue.application;

import com.ticket.queue.domain.QueuePolicy;
import com.ticket.queue.config.QueueProperties;
import com.ticket.queue.config.QueueAdmissionProperties;
import com.ticket.queue.domain.QueuePolicyStore;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class QueuePolicyResolver {

    private final QueueProperties queueProperties;
    private final QueueAdmissionProperties queueAdmissionProperties;
    private final QueuePolicyStore queuePolicyStore;

    public QueuePolicy resolve(final Long performanceId) {
        return queuePolicyStore.findByPerformanceId(performanceId)
                .orElseGet(this::defaultPolicy);
    }

    private QueuePolicy defaultPolicy() {
        return new QueuePolicy(
                queueProperties.getAdmitLimitPerTick(),
                queueProperties.getMaxActiveUsers(),
                queueProperties.getActiveTtl(),
                queueAdmissionProperties.getSessionTtl()
        );
    }
}

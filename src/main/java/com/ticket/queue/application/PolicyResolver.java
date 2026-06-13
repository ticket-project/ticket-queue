package com.ticket.queue.application;

import com.ticket.queue.domain.Policy;
import com.ticket.queue.config.QueueProperties;
import com.ticket.queue.domain.PolicyStore;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class PolicyResolver {

    private final QueueProperties queueProperties;
    private final PolicyStore policyStore;

    public Policy resolve(final Long performanceId) {
        return policyStore.findByPerformanceId(performanceId)
                .orElseGet(this::defaultPolicy);
    }

    private Policy defaultPolicy() {
        return new Policy(
                queueProperties.getDefaultMaxAdmitPerSecond(),
                queueProperties.getDefaultMaxActiveSessions(),
                queueProperties.getShoppingSessionTtl(),
                queueProperties.getDefaultQueueTtl()
        );
    }
}

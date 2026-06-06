package com.ticket.queue.application;

import com.ticket.queue.domain.QueuePolicy;
import com.ticket.queue.domain.QueueTicketStore;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class QueueAdmissionAdvancer {

    private final QueuePolicyResolver queuePolicyResolver;
    private final QueueTicketStore queueTicketStore;

    public void advance(final Long performanceId) {
        QueuePolicy policy = queuePolicyResolver.resolve(performanceId);
        if (policy.admitLimitPerTick() <= 0) {
            return;
        }
        queueTicketStore.admitWaitingBatch(
                performanceId,
                policy.admitLimitPerTick(),
                policy.maxActiveUsers(),
                policy.activeTtl()
        );
    }
}

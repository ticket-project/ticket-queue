package com.ticket.queue.application;

import com.ticket.queue.domain.QueuePolicy;
import com.ticket.queue.domain.QueueTicketStore;
import com.ticket.queue.infra.DistributedLock;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class QueueAdmissionAdvancer {

    private final QueuePolicyResolver queuePolicyResolver;
    private final QueueTicketStore queueTicketStore;

    @DistributedLock(prefix = "queue:advance", dynamicKey = "#performanceId", leaseTime = 5_000L)
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

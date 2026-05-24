package com.ticket.queue.domain.command;

import com.ticket.queue.domain.model.QueuePolicy;
import com.ticket.queue.domain.port.QueueTicketStore;
import com.ticket.queue.domain.service.QueuePolicyResolver;
import com.ticket.queue.domain.support.lock.DistributedLock;
import java.time.Clock;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class QueueAdmissionAdvancer {

    private final QueuePolicyResolver queuePolicyResolver;
    private final QueueTicketStore queueTicketStore;
    private final Clock clock;

    @DistributedLock(prefix = "queue:advance", dynamicKey = "#performanceId", leaseTime = 5_000L)
    public void advance(final Long performanceId) {
        QueuePolicy policy = queuePolicyResolver.resolve(performanceId);

        while (queueTicketStore.countActive(performanceId) < policy.maxActiveUsers()) {
            if (queueTicketStore.admitNextWaiting(
                    performanceId,
                    policy.entryTokenTtl(),
                    policy.entryRetention(),
                    LocalDateTime.now(clock)
            ).isEmpty()) {
                break;
            }
        }
    }
}
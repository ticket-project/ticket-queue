package com.ticket.queue.application;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ticket.queue.domain.QueuePolicy;
import com.ticket.queue.domain.QueueTicketStore;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class QueueAdmissionAdvancerTest {

    @Test
    void admission_uses_configured_tick_limit_as_batch_size() {
        QueuePolicyResolver policyResolver = org.mockito.Mockito.mock(QueuePolicyResolver.class);
        QueueTicketStore queueTicketStore = org.mockito.Mockito.mock(QueueTicketStore.class);
        QueueAdmissionAdvancer advancer = new QueueAdmissionAdvancer(policyResolver, queueTicketStore);
        QueuePolicy policy = new QueuePolicy(2, 10, Duration.ofMinutes(5), Duration.ofHours(1));

        when(policyResolver.resolve(1L)).thenReturn(policy);

        advancer.advance(1L);

        verify(queueTicketStore).admitWaitingBatch(1L, 2, 10, policy.activeTtl());
    }

    @Test
    void admission_supports_large_tick_limit_as_single_batch_call() {
        QueuePolicyResolver policyResolver = org.mockito.Mockito.mock(QueuePolicyResolver.class);
        QueueTicketStore queueTicketStore = org.mockito.Mockito.mock(QueueTicketStore.class);
        QueueAdmissionAdvancer advancer = new QueueAdmissionAdvancer(policyResolver, queueTicketStore);
        QueuePolicy policy = new QueuePolicy(50, 300, Duration.ofMinutes(5), Duration.ofHours(1));

        when(policyResolver.resolve(1L)).thenReturn(policy);

        advancer.advance(1L);

        verify(queueTicketStore).admitWaitingBatch(1L, 50, 300, policy.activeTtl());
    }
}

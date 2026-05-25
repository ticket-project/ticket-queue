package com.ticket.queue.application;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ticket.queue.domain.QueueTicketStore;
import java.util.Set;
import org.junit.jupiter.api.Test;

class QueueAdvancementSchedulerTest {

    @Test
    void waiting_performance마다_advancer를_호출한다() {
        QueueTicketStore queueTicketStore = mock(QueueTicketStore.class);
        QueueAdmissionAdvancer queueAdmissionAdvancer = mock(QueueAdmissionAdvancer.class);
        QueueAdvancementScheduler scheduler = new QueueAdvancementScheduler(queueTicketStore, queueAdmissionAdvancer);
        when(queueTicketStore.findWaitingPerformanceIds()).thenReturn(Set.of(1L, 2L));

        scheduler.advanceWaitingQueues();

        verify(queueAdmissionAdvancer).advance(1L);
        verify(queueAdmissionAdvancer).advance(2L);
    }
}

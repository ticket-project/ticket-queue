package com.ticket.queue.application;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.ticket.queue.domain.QueueAdvancementStore;
import java.util.Set;
import org.junit.jupiter.api.Test;

class AdvancementSchedulerTest {

    @Test
    void advances_each_waiting_performance_without_publishing_static_state() {
        QueueAdvancementStore queueAdvancementStore = mock(QueueAdvancementStore.class);
        AdmissionAdvancer admissionAdvancer = mock(AdmissionAdvancer.class);
        AdvancementScheduler scheduler = new AdvancementScheduler(queueAdvancementStore, admissionAdvancer);
        when(queueAdvancementStore.findWaitingPerformanceIds()).thenReturn(Set.of(1L, 2L));

        scheduler.advanceWaitingQueues();

        verify(admissionAdvancer).advance(1L);
        verify(admissionAdvancer).advance(2L);
        verify(queueAdvancementStore).findWaitingPerformanceIds();
        verifyNoMoreInteractions(queueAdvancementStore);
    }
}
package com.ticket.queue.application;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.ticket.queue.config.QueueProperties;
import com.ticket.queue.domain.AdmissionStateStore;
import java.util.Set;
import org.junit.jupiter.api.Test;

class AdvancementSchedulerTest {

    @Test
    void advances_each_waiting_performance_without_publishing_static_state() {
        QueueProperties queueProperties = new QueueProperties();
        AdmissionStateStore admissionStateStore = mock(AdmissionStateStore.class);
        AdmissionAdvancer admissionAdvancer = mock(AdmissionAdvancer.class);
        AdvancementScheduler scheduler = new AdvancementScheduler(
                queueProperties,
                admissionStateStore,
                admissionAdvancer
        );
        when(admissionStateStore.findWaitingPerformanceIds()).thenReturn(Set.of(1L, 2L));

        scheduler.advanceWaitingQueues();

        verify(admissionAdvancer).advance(1L);
        verify(admissionAdvancer).advance(2L);
        verify(admissionStateStore).findWaitingPerformanceIds();
        verifyNoMoreInteractions(admissionStateStore);
    }

    @Test
    void scheduler_can_be_disabled_by_property() {
        QueueProperties queueProperties = new QueueProperties();
        queueProperties.setSchedulerEnabled(false);
        AdmissionStateStore admissionStateStore = mock(AdmissionStateStore.class);
        AdmissionAdvancer admissionAdvancer = mock(AdmissionAdvancer.class);
        AdvancementScheduler scheduler = new AdvancementScheduler(
                queueProperties,
                admissionStateStore,
                admissionAdvancer
        );

        scheduler.advanceWaitingQueues();

        verifyNoInteractions(admissionStateStore);
        verifyNoInteractions(admissionAdvancer);
    }
}

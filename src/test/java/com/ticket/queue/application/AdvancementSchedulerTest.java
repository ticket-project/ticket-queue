package com.ticket.queue.application;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.ticket.queue.config.QueueProperties;
import com.ticket.queue.domain.AdmissionStateStore;
import com.ticket.queue.domain.PublicState;
import com.ticket.queue.domain.PublicStatePublisher;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

class AdvancementSchedulerTest {

    @Test
    void advances_each_waiting_performance() {
        QueueProperties queueProperties = new QueueProperties();
        AdmissionStateStore admissionStateStore = mock(AdmissionStateStore.class);
        AdmissionAdvancer admissionAdvancer = mock(AdmissionAdvancer.class);
        PublicStatePublisher publicStatePublisher = mock(PublicStatePublisher.class);
        AdvancementScheduler scheduler = new AdvancementScheduler(
                queueProperties,
                admissionStateStore,
                admissionAdvancer,
                publicStatePublisher
        );
        when(admissionStateStore.findWaitingPerformanceIds()).thenReturn(Set.of(1L, 2L));
        when(admissionStateStore.readPublicState(1L, 5_000L))
                .thenReturn(new PublicState(1L, "OPEN", 10L, 100L, 5_000L, 1_717_000_000_000L));
        when(admissionStateStore.readPublicState(2L, 5_000L))
                .thenReturn(new PublicState(2L, "OPEN", 20L, 200L, 5_000L, 1_717_000_000_001L));

        scheduler.advanceWaitingQueues();

        verify(admissionAdvancer).advance(1L);
        verify(admissionAdvancer).advance(2L);
        verify(publicStatePublisher).publish(new PublicState(1L, "OPEN", 10L, 100L, 5_000L, 1_717_000_000_000L));
        verify(publicStatePublisher).publish(new PublicState(2L, "OPEN", 20L, 200L, 5_000L, 1_717_000_000_001L));
    }

    @Test
    void publishes_public_state_after_advancing_performance() {
        QueueProperties queueProperties = new QueueProperties();
        AdmissionStateStore admissionStateStore = mock(AdmissionStateStore.class);
        AdmissionAdvancer admissionAdvancer = mock(AdmissionAdvancer.class);
        PublicStatePublisher publicStatePublisher = mock(PublicStatePublisher.class);
        AdvancementScheduler scheduler = new AdvancementScheduler(
                queueProperties,
                admissionStateStore,
                admissionAdvancer,
                publicStatePublisher
        );
        PublicState state = new PublicState(1L, "OPEN", 100L, 1_000L, 5_000L, 1_717_000_000_000L);
        when(admissionStateStore.findWaitingPerformanceIds()).thenReturn(Set.of(1L));
        when(admissionStateStore.readPublicState(1L, 5_000L)).thenReturn(state);

        scheduler.advanceWaitingQueues();

        InOrder inOrder = inOrder(admissionAdvancer, admissionStateStore, publicStatePublisher);
        inOrder.verify(admissionAdvancer).advance(1L);
        inOrder.verify(admissionStateStore).readPublicState(1L, 5_000L);
        inOrder.verify(publicStatePublisher).publish(state);
    }

    @Test
    void scheduler_can_be_disabled_by_property() {
        QueueProperties queueProperties = new QueueProperties();
        queueProperties.setSchedulerEnabled(false);
        AdmissionStateStore admissionStateStore = mock(AdmissionStateStore.class);
        AdmissionAdvancer admissionAdvancer = mock(AdmissionAdvancer.class);
        PublicStatePublisher publicStatePublisher = mock(PublicStatePublisher.class);
        AdvancementScheduler scheduler = new AdvancementScheduler(
                queueProperties,
                admissionStateStore,
                admissionAdvancer,
                publicStatePublisher
        );

        scheduler.advanceWaitingQueues();

        verifyNoInteractions(admissionStateStore);
        verifyNoInteractions(admissionAdvancer);
        verifyNoInteractions(publicStatePublisher);
    }
}

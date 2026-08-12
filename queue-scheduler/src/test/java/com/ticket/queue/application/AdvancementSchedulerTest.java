package com.ticket.queue.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.ticket.queue.config.AdvancementProperties;
import com.ticket.queue.domain.QueueAdvanceResult;
import com.ticket.queue.domain.QueueAdvancementStore;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.Set;
import org.junit.jupiter.api.Test;

class AdvancementSchedulerTest {

    @Test
    void advances_each_waiting_performance_without_publishing_static_state() {
        QueueAdvancementStore queueAdvancementStore = mock(QueueAdvancementStore.class);
        AdmissionAdvancer admissionAdvancer = mock(AdmissionAdvancer.class);
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        QueueSchedulerMetrics metrics = new QueueSchedulerMetrics(meterRegistry, new AdvancementProperties());
        AdvancementScheduler scheduler = new AdvancementScheduler(queueAdvancementStore, admissionAdvancer, metrics);
        when(queueAdvancementStore.findWaitingPerformanceIds()).thenReturn(Set.of(1L, 2L));
        when(admissionAdvancer.advance(1L)).thenReturn(QueueAdvanceResult.observed(2, 5, 100L));
        when(admissionAdvancer.advance(2L)).thenReturn(QueueAdvanceResult.observed(3, 7, 200L));

        scheduler.advanceWaitingQueues();

        verify(admissionAdvancer).advance(1L);
        verify(admissionAdvancer).advance(2L);
        verify(queueAdvancementStore).findWaitingPerformanceIds();
        verifyNoMoreInteractions(queueAdvancementStore);
        assertThat(
                meterRegistry.get("queue.scheduler.admitted").counter().count()
        ).isEqualTo(5.0);
        assertThat(
                meterRegistry.get("queue.scheduler.backlog").gauge().value()
        ).isEqualTo(12.0);
        assertThat(
                meterRegistry.get("queue.scheduler.oldest.pending.slot.age").gauge().value()
        ).isEqualTo(0.2);
        assertThat(
                meterRegistry.get("queue.scheduler.cycle.duration").tag("result", "success").timer().count()
        ).isEqualTo(1L);
    }

    @Test
    void records_partial_failure_and_keeps_processing_other_performances() {
        QueueAdvancementStore queueAdvancementStore = mock(QueueAdvancementStore.class);
        AdmissionAdvancer admissionAdvancer = mock(AdmissionAdvancer.class);
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        QueueSchedulerMetrics metrics = new QueueSchedulerMetrics(meterRegistry, new AdvancementProperties());
        AdvancementScheduler scheduler = new AdvancementScheduler(queueAdvancementStore, admissionAdvancer, metrics);
        when(queueAdvancementStore.findWaitingPerformanceIds()).thenReturn(Set.of(1L, 2L));
        when(admissionAdvancer.advance(1L)).thenThrow(new IllegalStateException("redis unavailable"));
        when(admissionAdvancer.advance(2L)).thenReturn(QueueAdvanceResult.observed(1, 4, 300L));

        scheduler.advanceWaitingQueues();

        verify(admissionAdvancer).advance(2L);
        assertThat(meterRegistry.get("queue.scheduler.advance")
                .tag("result", "advance_failure").counter().count()).isEqualTo(1.0);
        assertThat(meterRegistry.get("queue.scheduler.cycle.duration")
                .tag("result", "partial_failure").timer().count()).isEqualTo(1L);
        assertThat(meterRegistry.get("queue.scheduler.backlog").gauge().value()).isEqualTo(4.0);
    }

    @Test
    void records_and_rethrows_waiting_performance_discovery_failure() {
        QueueAdvancementStore queueAdvancementStore = mock(QueueAdvancementStore.class);
        AdmissionAdvancer admissionAdvancer = mock(AdmissionAdvancer.class);
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        QueueSchedulerMetrics metrics = new QueueSchedulerMetrics(meterRegistry, new AdvancementProperties());
        AdvancementScheduler scheduler = new AdvancementScheduler(queueAdvancementStore, admissionAdvancer, metrics);
        when(queueAdvancementStore.findWaitingPerformanceIds())
                .thenThrow(new IllegalStateException("redis unavailable"));

        assertThatExceptionOfType(IllegalStateException.class)
                .isThrownBy(scheduler::advanceWaitingQueues);

        assertThat(meterRegistry.get("queue.scheduler.cycle.duration")
                .tag("result", "waiting_performance_discovery_failure").timer().count()).isEqualTo(1L);
    }
}

package com.ticket.queue.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ticket.queue.config.AdvancementProperties;
import com.ticket.queue.domain.QueueAdvancementStore;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.mockito.verification.VerificationMode;

class AdvancementSchedulerTest {

    private AdvancementProperties properties;
    private QueueAdvancementStore queueAdvancementStore;
    private AdvancementScheduler scheduler;
    private SimpleMeterRegistry meterRegistry;

    @BeforeEach
    void setUp() {
        properties = new AdvancementProperties();
        properties.setAdvanceBatchSize(2);
        properties.setShardCount(128);
        properties.setSlotSizeMillis(50L);
        properties.setSlotCloseGraceMillis(200L);
        queueAdvancementStore = mock(QueueAdvancementStore.class);
        meterRegistry = new SimpleMeterRegistry();
        scheduler = new AdvancementScheduler(queueAdvancementStore, properties, meterRegistry);
        when(queueAdvancementStore.findWaitingPerformanceIds()).thenReturn(Set.of(1L));
    }

    @Test
    void advances_each_waiting_performance_with_configured_batch_size() {
        when(queueAdvancementStore.findWaitingPerformanceIds()).thenReturn(Set.of(1L, 2L));

        scheduler.advanceWaitingQueues();

        verify(queueAdvancementStore).advancePublicState(1L, 2, 128, 50L, 200L, Duration.ofHours(24), 5_000L);
        verify(queueAdvancementStore).advancePublicState(2L, 2, 128, 50L, 200L, Duration.ofHours(24), 5_000L);
    }

    @Test
    void exports_applied_batch_size_waiting_performances_and_admitted_users() {
        when(queueAdvancementStore.findWaitingPerformanceIds()).thenReturn(Set.of(1L, 2L));
        when(queueAdvancementStore.findAdvanceBatchSizeOverride()).thenReturn(Optional.of("70"));
        when(queueAdvancementStore.advancePublicState(
                eq(1L), anyInt(), anyInt(), anyLong(), anyLong(), any(Duration.class), anyLong()
        )).thenReturn(70);
        when(queueAdvancementStore.advancePublicState(
                eq(2L), anyInt(), anyInt(), anyLong(), anyLong(), any(Duration.class), anyLong()
        )).thenReturn(5);

        scheduler.advanceWaitingQueues();

        assertThat(gauge("queue.scheduler.advance.batch.size")).isEqualTo(70.0);
        assertThat(gauge("queue.scheduler.advance.batch.size.override")).isEqualTo(1.0);
        assertThat(gauge("queue.scheduler.waiting.performances")).isEqualTo(2.0);
        assertThat(counter("queue.scheduler.admitted")).isEqualTo(75.0);
    }

    @Test
    void exports_rejected_override_and_read_failure_counts() {
        when(queueAdvancementStore.findAdvanceBatchSizeOverride())
                .thenReturn(Optional.of("abc"))
                .thenThrow(new IllegalStateException("redis unavailable"));

        scheduler.advanceWaitingQueues();
        scheduler.advanceWaitingQueues();

        assertThat(counter("queue.scheduler.advance.batch.size.override.rejected")).isEqualTo(1.0);
        assertThat(counter("queue.scheduler.advance.batch.size.override.read.failures")).isEqualTo(1.0);
        assertThat(gauge("queue.scheduler.advance.batch.size")).isEqualTo(2.0);
        assertThat(gauge("queue.scheduler.advance.batch.size.override")).isEqualTo(0.0);
    }

    private double gauge(final String name) {
        return meterRegistry.get(name).gauge().value();
    }

    private double counter(final String name) {
        return meterRegistry.get(name).counter().count();
    }

    @Test
    void redis_override_replaces_configured_batch_size() {
        when(queueAdvancementStore.findAdvanceBatchSizeOverride()).thenReturn(Optional.of("70"));

        scheduler.advanceWaitingQueues();

        verify(queueAdvancementStore).advancePublicState(1L, 70, 128, 50L, 200L, Duration.ofHours(24), 5_000L);
    }

    @Test
    void zero_override_stops_admission_but_still_refreshes_public_state() {
        when(queueAdvancementStore.findAdvanceBatchSizeOverride()).thenReturn(Optional.of("0"));

        scheduler.advanceWaitingQueues();

        verify(queueAdvancementStore).advancePublicState(1L, 0, 128, 50L, 200L, Duration.ofHours(24), 5_000L);
    }

    @Test
    @SuppressWarnings("unchecked")
    void removed_override_returns_to_configured_batch_size() {
        when(queueAdvancementStore.findAdvanceBatchSizeOverride())
                .thenReturn(Optional.of("70"), Optional.empty());

        scheduler.advanceWaitingQueues();
        scheduler.advanceWaitingQueues();

        InOrder inOrder = inOrder(queueAdvancementStore);
        verifyAdvancedWith(inOrder, times(1), 70);
        verifyAdvancedWith(inOrder, times(1), 2);
    }

    @Test
    @SuppressWarnings("unchecked")
    void invalid_override_keeps_previous_batch_size() {
        when(queueAdvancementStore.findAdvanceBatchSizeOverride())
                .thenReturn(Optional.of("70"), Optional.of("abc"), Optional.of("-1"), Optional.of(" 30 "));

        scheduler.advanceWaitingQueues();
        scheduler.advanceWaitingQueues();
        scheduler.advanceWaitingQueues();
        scheduler.advanceWaitingQueues();

        InOrder inOrder = inOrder(queueAdvancementStore);
        verifyAdvancedWith(inOrder, times(3), 70);
        verifyAdvancedWith(inOrder, times(1), 30);
    }

    @Test
    void override_read_failure_keeps_stopped_admission_stopped() {
        when(queueAdvancementStore.findAdvanceBatchSizeOverride())
                .thenReturn(Optional.of("0"))
                .thenThrow(new IllegalStateException("redis unavailable"));

        scheduler.advanceWaitingQueues();
        scheduler.advanceWaitingQueues();

        verify(queueAdvancementStore, times(2)).advancePublicState(
                eq(1L), eq(0), anyInt(), anyLong(), anyLong(), any(Duration.class), anyLong()
        );
    }

    private void verifyAdvancedWith(
            final InOrder inOrder,
            final VerificationMode mode,
            final int advanceBatchSize
    ) {
        inOrder.verify(queueAdvancementStore, mode).advancePublicState(
                eq(1L), eq(advanceBatchSize), anyInt(), anyLong(), anyLong(), any(Duration.class), anyLong()
        );
    }
}

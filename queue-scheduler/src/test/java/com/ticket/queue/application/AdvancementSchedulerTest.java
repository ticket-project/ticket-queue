package com.ticket.queue.application;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ticket.queue.config.AdvancementProperties;
import com.ticket.queue.domain.QueueAdvancementStore;
import java.time.Duration;
import java.util.Set;
import org.junit.jupiter.api.Test;

class AdvancementSchedulerTest {

    @Test
    void advances_each_waiting_performance_with_configured_batch_size() {
        AdvancementProperties properties = new AdvancementProperties();
        properties.setAdvanceBatchSize(2);
        properties.setShardCount(128);
        properties.setSlotSizeMillis(50L);
        properties.setSlotCloseGraceMillis(200L);
        QueueAdvancementStore queueAdvancementStore = mock(QueueAdvancementStore.class);
        AdvancementScheduler scheduler = new AdvancementScheduler(queueAdvancementStore, properties);
        when(queueAdvancementStore.findWaitingPerformanceIds()).thenReturn(Set.of(1L, 2L));

        scheduler.advanceWaitingQueues();

        verify(queueAdvancementStore).advancePublicState(1L, 2, 128, 50L, 200L, Duration.ofHours(24), 5_000L);
        verify(queueAdvancementStore).advancePublicState(2L, 2, 128, 50L, 200L, Duration.ofHours(24), 5_000L);
    }
}

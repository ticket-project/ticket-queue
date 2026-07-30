package com.ticket.queue.application;

import static org.mockito.Mockito.verify;

import com.ticket.queue.config.AdvancementProperties;
import com.ticket.queue.domain.QueueAdvancementStore;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class AdmissionAdvancerTest {

    @Test
    void admission_uses_configured_tick_limit_as_batch_size() {
        AdvancementProperties queueProperties = new AdvancementProperties();
        queueProperties.setDefaultMaxAdmitPerSecond(2);
        queueProperties.setDefaultMaxActiveSessions(10);
        queueProperties.setShardCount(128);
        queueProperties.setSlotSizeMillis(50L);
        queueProperties.setSlotCloseGraceMillis(200L);
        queueProperties.setDefaultQueueTtl(Duration.ofHours(24));
        queueProperties.setDefaultRefreshAfterMs(5_000L);
        QueueAdvancementStore queueAdvancementStore = org.mockito.Mockito.mock(QueueAdvancementStore.class);
        AdmissionAdvancer advancer = new AdmissionAdvancer(queueProperties, queueAdvancementStore);

        advancer.advance(1L);

        verify(queueAdvancementStore).advancePublicState(
                1L,
                2,
                10,
                128,
                50L,
                200L,
                Duration.ofHours(24),
                5_000L
        );
    }

    @Test
    void admission_supports_large_tick_limit_as_single_batch_call() {
        AdvancementProperties queueProperties = new AdvancementProperties();
        queueProperties.setDefaultMaxAdmitPerSecond(50);
        queueProperties.setDefaultMaxActiveSessions(300);
        queueProperties.setShardCount(64);
        queueProperties.setSlotSizeMillis(25L);
        queueProperties.setSlotCloseGraceMillis(100L);
        QueueAdvancementStore queueAdvancementStore = org.mockito.Mockito.mock(QueueAdvancementStore.class);
        AdmissionAdvancer advancer = new AdmissionAdvancer(queueProperties, queueAdvancementStore);

        advancer.advance(1L);

        verify(queueAdvancementStore).advancePublicState(
                1L,
                50,
                300,
                64,
                25L,
                100L,
                Duration.ofHours(24),
                5_000L
        );
    }
}
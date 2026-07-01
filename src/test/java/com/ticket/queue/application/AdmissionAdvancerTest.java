package com.ticket.queue.application;

import static org.mockito.Mockito.verify;

import com.ticket.queue.config.QueueProperties;
import com.ticket.queue.domain.AdmissionStateStore;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class AdmissionAdvancerTest {

    @Test
    void admission_uses_configured_tick_limit_as_batch_size() {
        QueueProperties queueProperties = new QueueProperties();
        queueProperties.setDefaultMaxAdmitPerSecond(2);
        queueProperties.setDefaultMaxActiveSessions(10);
        queueProperties.setShardCount(128);
        queueProperties.setSlotSizeMillis(50L);
        queueProperties.setSlotCloseGraceMillis(200L);
        queueProperties.setDefaultQueueTtl(Duration.ofHours(24));
        queueProperties.setDefaultRefreshAfterMs(5_000L);
        AdmissionStateStore admissionStateStore = org.mockito.Mockito.mock(AdmissionStateStore.class);
        AdmissionAdvancer advancer = new AdmissionAdvancer(queueProperties, admissionStateStore);

        advancer.advance(1L);

        verify(admissionStateStore).advancePublicState(1L, 2, 10, 128, 50L, 200L, Duration.ofHours(24), 5_000L);
    }

    @Test
    void admission_supports_large_tick_limit_as_single_batch_call() {
        QueueProperties queueProperties = new QueueProperties();
        queueProperties.setDefaultMaxAdmitPerSecond(50);
        queueProperties.setDefaultMaxActiveSessions(300);
        queueProperties.setShardCount(64);
        queueProperties.setSlotSizeMillis(25L);
        queueProperties.setSlotCloseGraceMillis(100L);
        AdmissionStateStore admissionStateStore = org.mockito.Mockito.mock(AdmissionStateStore.class);
        AdmissionAdvancer advancer = new AdmissionAdvancer(queueProperties, admissionStateStore);

        advancer.advance(1L);

        verify(admissionStateStore).advancePublicState(1L, 50, 300, 64, 25L, 100L, Duration.ofHours(24), 5_000L);
    }
}

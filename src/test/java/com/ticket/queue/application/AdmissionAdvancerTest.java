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
        AdmissionStateStore admissionStateStore = org.mockito.Mockito.mock(AdmissionStateStore.class);
        AdmissionAdvancer advancer = new AdmissionAdvancer(queueProperties, admissionStateStore);

        advancer.advance(1L);

        verify(admissionStateStore).advancePublicState(1L, 2, 10, Duration.ofHours(24));
    }

    @Test
    void admission_supports_large_tick_limit_as_single_batch_call() {
        QueueProperties queueProperties = new QueueProperties();
        queueProperties.setDefaultMaxAdmitPerSecond(50);
        queueProperties.setDefaultMaxActiveSessions(300);
        AdmissionStateStore admissionStateStore = org.mockito.Mockito.mock(AdmissionStateStore.class);
        AdmissionAdvancer advancer = new AdmissionAdvancer(queueProperties, admissionStateStore);

        advancer.advance(1L);

        verify(admissionStateStore).advancePublicState(1L, 50, 300, Duration.ofHours(24));
    }
}

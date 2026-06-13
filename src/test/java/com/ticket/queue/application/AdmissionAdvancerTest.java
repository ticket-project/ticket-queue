package com.ticket.queue.application;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ticket.queue.domain.Policy;
import com.ticket.queue.domain.AdmissionStateStore;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class AdmissionAdvancerTest {

    @Test
    void admission_uses_configured_tick_limit_as_batch_size() {
        PolicyResolver policyResolver = org.mockito.Mockito.mock(PolicyResolver.class);
        AdmissionStateStore admissionStateStore = org.mockito.Mockito.mock(AdmissionStateStore.class);
        AdmissionAdvancer advancer = new AdmissionAdvancer(policyResolver, admissionStateStore);
        Policy policy = new Policy(2, 10, Duration.ofMinutes(5), Duration.ofHours(1));

        when(policyResolver.resolve(1L)).thenReturn(policy);

        advancer.advance(1L);

        verify(admissionStateStore).advancePublicState(1L, 2, 10);
    }

    @Test
    void admission_supports_large_tick_limit_as_single_batch_call() {
        PolicyResolver policyResolver = org.mockito.Mockito.mock(PolicyResolver.class);
        AdmissionStateStore admissionStateStore = org.mockito.Mockito.mock(AdmissionStateStore.class);
        AdmissionAdvancer advancer = new AdmissionAdvancer(policyResolver, admissionStateStore);
        Policy policy = new Policy(50, 300, Duration.ofMinutes(5), Duration.ofHours(1));

        when(policyResolver.resolve(1L)).thenReturn(policy);

        advancer.advance(1L);

        verify(admissionStateStore).advancePublicState(1L, 50, 300);
    }
}

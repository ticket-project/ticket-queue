package com.ticket.queue.application;

import com.ticket.queue.domain.Policy;
import com.ticket.queue.domain.AdmissionStateStore;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class AdmissionAdvancer {

    private final PolicyResolver policyResolver;
    private final AdmissionStateStore admissionStateStore;

    public void advance(final Long performanceId) {
        Policy policy = policyResolver.resolve(performanceId);
        if (policy.admitLimitPerTick() <= 0) {
            return;
        }
        admissionStateStore.advancePublicState(
                performanceId,
                policy.admitLimitPerTick(),
                policy.maxActiveUsers()
        );
    }
}

package com.ticket.queue.application;

import com.ticket.queue.config.QueueProperties;
import com.ticket.queue.domain.AdmissionStateStore;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class AdmissionAdvancer {

    private final QueueProperties queueProperties;
    private final AdmissionStateStore admissionStateStore;

    public void advance(final Long performanceId) {
        if (queueProperties.getDefaultMaxAdmitPerSecond() <= 0) {
            return;
        }
        admissionStateStore.advancePublicState(
                performanceId,
                queueProperties.getDefaultMaxAdmitPerSecond(),
                queueProperties.getDefaultMaxActiveSessions(),
                queueProperties.getShardCount(),
                queueProperties.getSlotSizeMillis(),
                queueProperties.getSlotCloseGraceMillis(),
                queueProperties.getDefaultQueueTtl(),
                queueProperties.getDefaultRefreshAfterMs()
        );
    }
}

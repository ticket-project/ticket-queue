package com.ticket.queue.application;

import com.ticket.queue.config.AdvancementProperties;
import com.ticket.queue.domain.QueueAdvancementStore;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class AdmissionAdvancer {

    private final AdvancementProperties queueProperties;
    private final QueueAdvancementStore queueAdvancementStore;

    public void advance(final Long performanceId) {
        if (queueProperties.getDefaultMaxAdmitPerSecond() <= 0) {
            return;
        }
        queueAdvancementStore.advancePublicState(
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

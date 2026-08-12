package com.ticket.queue.application;

import com.ticket.queue.config.AdvancementProperties;
import com.ticket.queue.domain.QueueAdvanceResult;
import com.ticket.queue.domain.QueueAdvancementStore;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class AdmissionAdvancer {

    private final AdvancementProperties queueProperties;
    private final QueueAdvancementStore queueAdvancementStore;

    public QueueAdvanceResult advance(final Long performanceId) {
        if (queueProperties.getAdvanceBatchSize() <= 0) {
            return QueueAdvanceResult.skipped();
        }
        return queueAdvancementStore.advancePublicState(
                performanceId,
                queueProperties.getAdvanceBatchSize(),
                queueProperties.getShardCount(),
                queueProperties.getSlotSizeMillis(),
                queueProperties.getSlotCloseGraceMillis(),
                queueProperties.getDefaultQueueTtl(),
                queueProperties.getDefaultRefreshAfterMs()
        );
    }
}

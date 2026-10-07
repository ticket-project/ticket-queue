package com.ticket.queue.application;

import com.ticket.queue.config.AdvancementProperties;
import com.ticket.queue.domain.QueueAdvancementStore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class AdvancementScheduler {

    private final QueueAdvancementStore queueAdvancementStore;
    private final AdvancementProperties properties;

    @Scheduled(fixedDelayString = "${app.queue.advance-interval-ms:1000}")
    public void advanceWaitingQueues() {
        queueAdvancementStore.findWaitingPerformanceIds()
                .forEach(this::advanceSafely);
    }

    private void advanceSafely(final Long performanceId) {
        try {
            queueAdvancementStore.advancePublicState(
                    performanceId,
                    properties.getAdvanceBatchSize(),
                    properties.getShardCount(),
                    properties.getSlotSizeMillis(),
                    properties.getSlotCloseGraceMillis(),
                    properties.getDefaultQueueTtl(),
                    properties.getDefaultRefreshAfterMs()
            );
        } catch (RuntimeException exception) {
            log.warn("failed to advance queue performanceId={}", performanceId, exception);
        }
    }
}

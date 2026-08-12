package com.ticket.queue.application;

import com.ticket.queue.domain.QueueAdvanceResult;
import com.ticket.queue.domain.QueueAdvancementStore;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class AdvancementScheduler {

    private final QueueAdvancementStore queueAdvancementStore;
    private final AdmissionAdvancer admissionAdvancer;
    private final QueueSchedulerMetrics queueSchedulerMetrics;

    @Scheduled(fixedDelayString = "${app.queue.advance-interval-ms:1000}")
    public void advanceWaitingQueues() {
        long startedNanos = System.nanoTime();
        Set<Long> performanceIds;
        try {
            performanceIds = queueAdvancementStore.findWaitingPerformanceIds();
        } catch (RuntimeException exception) {
            queueSchedulerMetrics.recordDiscoveryFailure(System.nanoTime() - startedNanos);
            log.warn("failed to discover waiting queues reason=waiting_performance_discovery_failure", exception);
            throw exception;
        }

        List<QueueAdvanceResult> results = new ArrayList<>(performanceIds.size());
        int failureCount = 0;
        for (Long performanceId : performanceIds) {
            QueueAdvanceResult result = advanceSafely(performanceId);
            if (result == null) {
                failureCount++;
            } else {
                results.add(result);
            }
        }
        queueSchedulerMetrics.recordQueueSnapshot(performanceIds.size(), results);
        queueSchedulerMetrics.recordCycle(System.nanoTime() - startedNanos, failureCount > 0);
    }

    private QueueAdvanceResult advanceSafely(final Long performanceId) {
        try {
            QueueAdvanceResult result = admissionAdvancer.advance(performanceId);
            queueSchedulerMetrics.recordAdvance(result);
            return result;
        } catch (RuntimeException exception) {
            queueSchedulerMetrics.recordAdvanceFailure();
            log.warn("failed to advance queue reason=advance_failure performanceId={}", performanceId, exception);
            return null;
        }
    }
}

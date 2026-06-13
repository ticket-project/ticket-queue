package com.ticket.queue.application;

import com.ticket.queue.config.QueueProperties;
import com.ticket.queue.domain.AdmissionStateStore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class AdvancementScheduler {

    private final QueueProperties queueProperties;
    private final AdmissionStateStore admissionStateStore;
    private final AdmissionAdvancer admissionAdvancer;

    @Scheduled(fixedDelayString = "${app.queue.advance-interval-ms:1000}")
    public void advanceWaitingQueues() {
        if (!queueProperties.isSchedulerEnabled()) {
            return;
        }
        admissionStateStore.findWaitingPerformanceIds()
                .forEach(this::advanceSafely);
    }

    private void advanceSafely(final Long performanceId) {
        try {
            admissionAdvancer.advance(performanceId);
        } catch (RuntimeException exception) {
            log.warn("failed to advance queue performanceId={}", performanceId, exception);
        }
    }
}

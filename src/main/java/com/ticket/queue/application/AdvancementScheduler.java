package com.ticket.queue.application;

import com.ticket.queue.config.QueueProperties;
import com.ticket.queue.domain.AdmissionStateStore;
import com.ticket.queue.domain.PublicState;
import com.ticket.queue.domain.PublicStatePublisher;
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
    private final PublicStatePublisher publicStatePublisher;

    @Scheduled(fixedDelayString = "${app.queue.advance-interval-ms:1000}")
    public void advanceWaitingQueues() {
        if (!queueProperties.isSchedulerEnabled()) {
            return;
        }
        admissionStateStore.findWaitingPerformanceIds()
                .forEach(this::advanceAndPublishSafely);
    }

    private void advanceAndPublishSafely(final Long performanceId) {
        try {
            admissionAdvancer.advance(performanceId);
            publishPublicState(performanceId);
        } catch (RuntimeException exception) {
            log.warn("failed to advance queue performanceId={}", performanceId, exception);
        }
    }

    private void publishPublicState(final Long performanceId) {
        PublicState publicState = admissionStateStore.readPublicState(
                performanceId,
                queueProperties.getDefaultRefreshAfterMs()
        );
        publicStatePublisher.publish(publicState);
    }
}

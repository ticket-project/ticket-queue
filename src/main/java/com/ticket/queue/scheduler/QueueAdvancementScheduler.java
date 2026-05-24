package com.ticket.queue.scheduler;

import com.ticket.queue.domain.command.QueueAdmissionAdvancer;
import com.ticket.queue.domain.port.QueueTicketStore;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class QueueAdvancementScheduler {

    private final QueueTicketStore queueTicketStore;
    private final QueueAdmissionAdvancer queueAdmissionAdvancer;

    @Scheduled(fixedDelayString = "${app.queue.advance-interval-ms:1000}")
    public void advanceWaitingQueues() {
        queueTicketStore.findWaitingPerformanceIds()
                .forEach(queueAdmissionAdvancer::advance);
    }
}
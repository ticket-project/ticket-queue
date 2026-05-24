package com.ticket.queue.domain.command.exit;

import com.ticket.queue.domain.command.QueueAdmissionAdvancer;
import com.ticket.queue.domain.model.QueueTicket;
import com.ticket.queue.domain.port.QueueTicketStore;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class QueueExitProcessor {

    private final QueueTicketStore queueTicketStore;
    private final QueueAdmissionAdvancer queueAdmissionAdvancer;

    public void exit(final Long performanceId, final Long memberId) {
        queueTicketStore.findTicket(performanceId, memberId)
                .ifPresent(ticket -> exit(performanceId, memberId, ticket));
    }

    private void exit(final Long performanceId, final Long memberId, final QueueTicket ticket) {
        ticket.assertOwnedBy(performanceId, memberId);
        if (ticket.isWaiting()) {
            queueTicketStore.leaveWaiting(performanceId, memberId);
            return;
        }
        if (ticket.isAdmitted()) {
            queueTicketStore.leaveAdmitted(performanceId, memberId);
            queueAdmissionAdvancer.advance(performanceId);
        }
    }
}
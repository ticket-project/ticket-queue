package com.ticket.queue.application;

import com.ticket.queue.domain.QueueEntryStatus;
import com.ticket.queue.domain.QueueTicket;
import com.ticket.queue.domain.QueueTicketStore;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class QueueStatusReader {

    private final QueueTicketStore queueTicketStore;

    public GetQueueStatusUseCase.Output read(final Long performanceId, final String queueSessionId) {
        return queueTicketStore.findTicket(performanceId, queueSessionId)
                .map(ticket -> read(performanceId, queueSessionId, ticket))
                .orElseGet(() -> new GetQueueStatusUseCase.Output(QueueEntryStatus.EXPIRED, null, null));
    }

    private GetQueueStatusUseCase.Output read(
            final Long performanceId,
            final String queueSessionId,
            final QueueTicket ticket
    ) {
        if (ticket.isWaiting()) {
            return queueTicketStore.findWaitingPosition(performanceId, queueSessionId)
                    .map(position -> new GetQueueStatusUseCase.Output(QueueEntryStatus.WAITING, position, null))
                    .orElseGet(() -> new GetQueueStatusUseCase.Output(QueueEntryStatus.EXPIRED, null, null));
        }
        if (ticket.isAdmitted()) {
            return new GetQueueStatusUseCase.Output(QueueEntryStatus.ADMITTED, null, ticket.activeTtl());
        }
        return new GetQueueStatusUseCase.Output(QueueEntryStatus.EXPIRED, null, null);
    }
}

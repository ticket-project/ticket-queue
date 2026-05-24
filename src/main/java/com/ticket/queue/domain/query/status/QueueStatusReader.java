package com.ticket.queue.domain.query.status;

import com.ticket.queue.domain.model.QueueEntryStatus;
import com.ticket.queue.domain.model.QueueTicket;
import com.ticket.queue.domain.port.QueueTicketStore;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class QueueStatusReader {

    private final QueueTicketStore queueTicketStore;

    public GetQueueStatusUseCase.Output read(final Long performanceId, final Long memberId) {
        return queueTicketStore.findTicket(performanceId, memberId)
                .map(ticket -> read(performanceId, memberId, ticket))
                .orElseGet(() -> new GetQueueStatusUseCase.Output(QueueEntryStatus.EXPIRED, null));
    }

    private GetQueueStatusUseCase.Output read(
            final Long performanceId,
            final Long memberId,
            final QueueTicket ticket
    ) {
        ticket.assertOwnedBy(performanceId, memberId);
        if (ticket.isWaiting()) {
            return queueTicketStore.findWaitingPosition(performanceId, memberId)
                    .map(position -> new GetQueueStatusUseCase.Output(QueueEntryStatus.WAITING, position))
                    .orElseGet(() -> new GetQueueStatusUseCase.Output(QueueEntryStatus.EXPIRED, null));
        }
        if (ticket.isAdmitted()) {
            return new GetQueueStatusUseCase.Output(QueueEntryStatus.ADMITTED, null);
        }
        return new GetQueueStatusUseCase.Output(QueueEntryStatus.EXPIRED, null);
    }
}
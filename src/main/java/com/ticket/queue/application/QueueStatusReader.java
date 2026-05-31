package com.ticket.queue.application;

import com.ticket.queue.domain.QueueEntryStatus;
import com.ticket.queue.domain.QueueTicket;
import com.ticket.queue.domain.QueueTicketStore;
import java.time.Duration;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class QueueStatusReader {

    private final QueueTicketStore queueTicketStore;

    public Result read(final Long performanceId, final String queueSessionId) {
        return queueTicketStore.findTicket(performanceId, queueSessionId)
                .map(ticket -> read(performanceId, queueSessionId, ticket))
                .orElseGet(() -> new Result(QueueEntryStatus.EXPIRED, null, null));
    }

    private Result read(
            final Long performanceId,
            final String queueSessionId,
            final QueueTicket ticket
    ) {
        if (ticket.isWaiting()) {
            return queueTicketStore.findWaitingPosition(performanceId, queueSessionId)
                    .map(position -> new Result(QueueEntryStatus.WAITING, position, null))
                    .orElseGet(() -> new Result(QueueEntryStatus.EXPIRED, null, null));
        }
        if (ticket.isAdmitted()) {
            return new Result(QueueEntryStatus.ADMITTED, null, ticket.activeTtl());
        }
        return new Result(QueueEntryStatus.EXPIRED, null, null);
    }

    public record Result(QueueEntryStatus status, Long position, Duration activeTtl) {
    }
}

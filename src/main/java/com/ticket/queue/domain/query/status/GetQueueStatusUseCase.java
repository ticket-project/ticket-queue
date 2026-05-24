package com.ticket.queue.domain.query.status;

import com.ticket.queue.domain.model.QueueEntryStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class GetQueueStatusUseCase {

    private final QueueStatusReader queueStatusReader;

    public Output execute(final Input input) {
        return queueStatusReader.read(input.performanceId(), input.memberId());
    }

    public record Input(Long performanceId, Long memberId) {
    }

    public record Output(QueueEntryStatus status, Long position) {
    }
}
package com.ticket.queue.application;

import com.ticket.queue.domain.QueueEntryStatus;
import java.time.Duration;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class GetQueueStatusUseCase {

    private final QueueStatusReader queueStatusReader;

    public Output execute(final Input input) {
        return queueStatusReader.read(input.performanceId(), input.queueSessionId());
    }

    public record Input(Long performanceId, String queueSessionId) {
    }

    public record Output(QueueEntryStatus status, Long position, Duration activeTtl) {
    }
}

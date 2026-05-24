package com.ticket.queue.domain.command.exit;

import com.ticket.queue.domain.support.lock.DistributedLock;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class ExitQueueUseCase {

    private final QueueExitProcessor queueExitProcessor;

    @DistributedLock(prefix = "queue:exit", dynamicKey = "#input.performanceId() + ':' + #input.memberId()", leaseTime = 3_000L)
    public void execute(final Input input) {
        queueExitProcessor.exit(input.performanceId(), input.memberId());
    }

    public record Input(Long performanceId, Long memberId) {
    }
}
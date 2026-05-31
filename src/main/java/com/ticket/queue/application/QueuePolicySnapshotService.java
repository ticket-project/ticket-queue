package com.ticket.queue.application;

import com.ticket.queue.domain.QueueMode;
import com.ticket.queue.domain.QueuePolicy;
import com.ticket.queue.domain.QueuePolicyStore;
import java.time.Duration;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class QueuePolicySnapshotService {

    private final QueuePolicyStore queuePolicyStore;

    public void save(
            final Long performanceId,
            final int admitLimitPerTick,
            final int maxActiveUsers,
            final Duration activeTtl,
            final Duration sessionTtl,
            final QueueMode queueMode,
            final LocalDateTime preopenQueueStartAt,
            final LocalDateTime orderCloseTime
    ) {
        queuePolicyStore.save(
                performanceId,
                new QueuePolicy(
                        admitLimitPerTick,
                        maxActiveUsers,
                        activeTtl,
                        sessionTtl,
                        queueMode,
                        preopenQueueStartAt,
                        orderCloseTime
                )
        );
    }
}

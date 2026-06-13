package com.ticket.queue.application;

import com.ticket.queue.domain.PolicyMode;
import com.ticket.queue.domain.Policy;
import com.ticket.queue.domain.PolicyStore;
import java.time.Duration;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class PolicySnapshotService {

    private final PolicyStore policyStore;

    public void save(
            final Long performanceId,
            final int admitLimitPerTick,
            final int maxActiveUsers,
            final Duration activeTtl,
            final Duration sessionTtl,
            final PolicyMode queueMode,
            final LocalDateTime preopenQueueStartAt,
            final LocalDateTime orderCloseTime
    ) {
        policyStore.save(
                performanceId,
                new Policy(
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

package com.ticket.queue.api.dto;

import com.ticket.queue.domain.JoinResult;

public record JoinResponse(
        Long performanceId,
        String queueId,
        String status,
        String queueToken,
        int shardId,
        Long localSeq,
        Long slotId,
        Long slotStartMillis,
        Long pollAfterMs
) {

    private static final String WAITING = "WAITING";

    public static JoinResponse waiting(
            final Long performanceId,
            final JoinResult joinResult,
            final String queueToken,
            final long pollAfterMs
    ) {
        return new JoinResponse(
                performanceId,
                joinResult.queueId(),
                WAITING,
                queueToken,
                joinResult.shardId(),
                joinResult.localSeq(),
                joinResult.slotId(),
                joinResult.slotStartMillis(),
                pollAfterMs
        );
    }
}

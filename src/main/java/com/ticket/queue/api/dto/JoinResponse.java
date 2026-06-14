package com.ticket.queue.api.dto;

import com.ticket.queue.domain.JoinResult;

public record JoinResponse(
        Long performanceId,
        String queueId,
        Long seq,
        String status,
        String queueToken
) {

    private static final String WAITING = "WAITING";

    public static JoinResponse waiting(
            final Long performanceId,
            final JoinResult joinResult,
            final String queueToken
    ) {
        return new JoinResponse(performanceId, joinResult.queueId(), joinResult.seq(), WAITING, queueToken);
    }
}

package com.ticket.queue.api.dto;

import com.ticket.queue.domain.PublicState;
import java.util.Map;

public record PublicStateResponse(
        Long performanceId,
        String status,
        int shardCount,
        long slotSizeMillis,
        Map<Integer, Long> serving,
        Map<Integer, Long> tail,
        Long refreshAfterMs,
        Long serverTimeMillis
) {

    public static PublicStateResponse from(final PublicState publicState) {
        return new PublicStateResponse(
                publicState.performanceId(),
                publicState.status(),
                publicState.shardCount(),
                publicState.slotSizeMillis(),
                publicState.serving(),
                publicState.tail(),
                publicState.refreshAfterMs(),
                publicState.serverTimeMillis()
        );
    }
}

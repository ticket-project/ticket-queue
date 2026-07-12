package com.ticket.queue.domain;

import java.util.Map;

public record PublicState(
        Long performanceId,
        String status,
        int shardCount,
        long slotSizeMillis,
        Map<Integer, Long> serving,
        Map<Integer, Long> tail,
        Long refreshAfterMs,
        Long serverTimeMillis
) {
}

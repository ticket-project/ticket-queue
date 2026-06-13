package com.ticket.queue.api;

public record PublicStateResponse(
        Long performanceId,
        String status,
        Long admittedUntilSeq,
        Long tailSeq,
        Long refreshAfterMs,
        Long serverTimeMillis
) {
}

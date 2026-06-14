package com.ticket.queue.domain;

public record PublicState(
        Long performanceId,
        String status,
        Long admittedUntilSeq,
        Long tailSeq,
        Long refreshAfterMs,
        Long serverTimeMillis
) {
}

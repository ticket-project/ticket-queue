package com.ticket.queue.api.dto;

import com.ticket.queue.domain.PublicState;

public record PublicStateResponse(
        Long performanceId,
        String status,
        Long admittedUntilSeq,
        Long tailSeq,
        Long refreshAfterMs,
        Long serverTimeMillis
) {

    public static PublicStateResponse from(final PublicState publicState) {
        return new PublicStateResponse(
                publicState.performanceId(),
                publicState.status(),
                publicState.admittedUntilSeq(),
                publicState.tailSeq(),
                publicState.refreshAfterMs(),
                publicState.serverTimeMillis()
        );
    }
}

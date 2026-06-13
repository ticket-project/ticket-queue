package com.ticket.queue.api;

public record EnterResponse(
        String status,
        String admissionToken,
        Long expiresAtMillis,
        String redirectUrl
) {
}

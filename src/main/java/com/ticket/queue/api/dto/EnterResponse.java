package com.ticket.queue.api.dto;

public record EnterResponse(
        String status,
        String admissionToken,
        Long expiresAtMillis,
        String redirectUrl
) {

    private static final String ACTIVE = "ACTIVE";

    public static EnterResponse active(
            final String admissionToken,
            final Long expiresAtMillis,
            final String redirectUrl
    ) {
        return new EnterResponse(ACTIVE, admissionToken, expiresAtMillis, redirectUrl);
    }
}

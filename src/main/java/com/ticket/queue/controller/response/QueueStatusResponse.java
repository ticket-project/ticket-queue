package com.ticket.queue.controller.response;

public record QueueStatusResponse(
        String status,
        String queueSessionId,
        Long position,
        Long estimatedWaitSeconds,
        Long pollAfterSeconds,
        String admissionToken,
        String redirectUrl
) {

    public static QueueStatusResponse waiting(
            final String queueSessionId,
            final Long position,
            final Long estimatedWaitSeconds,
            final Long pollAfterSeconds
    ) {
        return new QueueStatusResponse(
                "WAITING",
                queueSessionId,
                position,
                estimatedWaitSeconds,
                pollAfterSeconds,
                null,
                null
        );
    }

    public static QueueStatusResponse active(
            final String queueSessionId,
            final String admissionToken,
            final String redirectUrl
    ) {
        return new QueueStatusResponse(
                "ACTIVE",
                queueSessionId,
                null,
                0L,
                null,
                admissionToken,
                redirectUrl
        );
    }

    public static QueueStatusResponse expired(final String queueSessionId) {
        return new QueueStatusResponse(
                "EXPIRED",
                queueSessionId,
                null,
                null,
                null,
                null,
                null
        );
    }
}
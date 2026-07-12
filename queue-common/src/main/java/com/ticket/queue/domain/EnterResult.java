package com.ticket.queue.domain;

public record EnterResult(
        Status status,
        String admissionToken,
        Long expiresAtMillis
) {

    public enum Status {
        ADMITTED,
        NOT_ADMITTED,
        FULL,
        EXPIRED
    }

    public static EnterResult admitted(
            final String admissionToken,
            final Long expiresAtMillis
    ) {
        return new EnterResult(Status.ADMITTED, admissionToken, expiresAtMillis);
    }

    public static EnterResult notAdmitted() {
        return new EnterResult(Status.NOT_ADMITTED, null, null);
    }

    public static EnterResult full() {
        return new EnterResult(Status.FULL, null, null);
    }

    public static EnterResult expired() {
        return new EnterResult(Status.EXPIRED, null, null);
    }
}

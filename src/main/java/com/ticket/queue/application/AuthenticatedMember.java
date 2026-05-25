package com.ticket.queue.application;

public record AuthenticatedMember(
        Long memberId,
        String role
) {
}

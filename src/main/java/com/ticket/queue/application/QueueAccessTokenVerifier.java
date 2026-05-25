package com.ticket.queue.application;

public interface QueueAccessTokenVerifier {

    AuthenticatedMember verify(String authorizationHeader);
}

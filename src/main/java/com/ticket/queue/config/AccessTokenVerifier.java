package com.ticket.queue.config;

@FunctionalInterface
public interface AccessTokenVerifier {

    AuthenticatedMember verify(String authorizationHeader);
}

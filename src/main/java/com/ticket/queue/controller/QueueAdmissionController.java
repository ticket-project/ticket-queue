package com.ticket.queue.controller;

import com.ticket.queue.controller.response.QueueStatusResponse;
import com.ticket.queue.service.QueueAdmissionService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/queue/performances")
public class QueueAdmissionController {

    private static final String BEARER_PREFIX = "Bearer ";
    private static final String QUEUE_SESSION_HEADER = "X-Queue-Session";

    private final QueueAdmissionService queueAdmissionService;

    @PostMapping("/{performanceId}/enter")
    public ResponseEntity<QueueStatusResponse> enter(
            @PathVariable final Long performanceId,
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) final String authorizationHeader
    ) {
        return ResponseEntity.ok(queueAdmissionService.enter(performanceId, extractBearerToken(authorizationHeader)));
    }

    @GetMapping("/{performanceId}/status")
    public ResponseEntity<QueueStatusResponse> status(
            @PathVariable final Long performanceId,
            @RequestHeader(QUEUE_SESSION_HEADER) final String queueSessionId
    ) {
        return ResponseEntity.ok(queueAdmissionService.status(performanceId, queueSessionId));
    }

    @PostMapping("/{performanceId}/leave")
    public ResponseEntity<Void> leave(
            @PathVariable final Long performanceId,
            @RequestHeader(QUEUE_SESSION_HEADER) final String queueSessionId
    ) {
        queueAdmissionService.leave(performanceId, queueSessionId);
        return ResponseEntity.noContent().build();
    }

    private String extractBearerToken(final String authorizationHeader) {
        if (authorizationHeader == null || !authorizationHeader.startsWith(BEARER_PREFIX)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "access token required");
        }

        String token = authorizationHeader.substring(BEARER_PREFIX.length()).trim();
        if (token.isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "access token required");
        }
        return token;
    }
}
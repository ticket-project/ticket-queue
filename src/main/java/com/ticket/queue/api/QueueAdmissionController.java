package com.ticket.queue.api;

import com.ticket.queue.application.QueueAdmissionService;
import com.ticket.support.passport.Passport;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/queue/performances")
public class QueueAdmissionController {

    private static final String QUEUE_SESSION_HEADER = "X-Queue-Session";

    private final QueueAdmissionService queueAdmissionService;

    @PostMapping("/{performanceId}/enter")
    public ResponseEntity<QueueStatusResponse> enter(
            @PathVariable final Long performanceId,
            final Passport passport
    ) {
        return ResponseEntity.ok(queueAdmissionService.enter(performanceId, passport));
    }

    @GetMapping("/{performanceId}/status")
    public ResponseEntity<QueueStatusResponse> status(
            @PathVariable final Long performanceId,
            @RequestHeader(QUEUE_SESSION_HEADER) final String queueSessionId
    ) {
        return ResponseEntity.ok(queueAdmissionService.status(performanceId, queueSessionId));
    }

}

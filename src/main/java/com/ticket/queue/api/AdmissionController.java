package com.ticket.queue.api;

import com.ticket.queue.application.AdmissionService;
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
public class AdmissionController {

    private static final String QUEUE_TOKEN_HEADER = "X-Queue-Token";

    private final AdmissionService admissionService;

    @PostMapping("/{performanceId}/join")
    public ResponseEntity<JoinResponse> join(
            @PathVariable final Long performanceId,
            final Passport passport
    ) {
        return ResponseEntity.ok(admissionService.join(performanceId, passport));
    }

    @GetMapping("/{performanceId}/state")
    public ResponseEntity<PublicStateResponse> state(
            @PathVariable final Long performanceId
    ) {
        return ResponseEntity.ok(admissionService.state(performanceId));
    }

    @PostMapping("/{performanceId}/enter")
    public ResponseEntity<EnterResponse> enter(
            @PathVariable final Long performanceId,
            @RequestHeader(QUEUE_TOKEN_HEADER) final String queueToken
    ) {
        return ResponseEntity.ok(admissionService.enter(performanceId, queueToken));
    }

}

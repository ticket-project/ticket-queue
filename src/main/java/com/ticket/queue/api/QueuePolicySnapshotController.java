package com.ticket.queue.api;

import com.ticket.queue.application.QueuePolicySnapshotService;
import com.ticket.support.passport.Passport;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/internal/v1/queue/performances")
public class QueuePolicySnapshotController {

    private final QueuePolicySnapshotService queuePolicySnapshotService;

    @PutMapping("/{performanceId}/policy")
    public ResponseEntity<Void> save(
            @PathVariable final Long performanceId,
            final Passport passport,
            @Valid @RequestBody final QueuePolicySnapshotRequest request
    ) {
        queuePolicySnapshotService.save(
                performanceId,
                request.admitLimitPerTick(),
                request.maxActiveUsers(),
                request.activeTtl(),
                request.sessionTtl(),
                request.resolvedQueueMode(),
                request.preopenQueueStartAt(),
                request.orderCloseTime()
        );
        return ResponseEntity.noContent().build();
    }
}

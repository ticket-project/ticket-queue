package com.ticket.queue.application;

import com.ticket.queue.api.QueueStatusResponse;

public interface QueueAdmissionService {

    QueueStatusResponse enter(Long performanceId, String authorizationHeader);

    QueueStatusResponse status(Long performanceId, String queueSessionId);
}

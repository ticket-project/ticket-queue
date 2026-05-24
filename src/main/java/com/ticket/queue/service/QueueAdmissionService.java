package com.ticket.queue.service;

import com.ticket.queue.controller.response.QueueStatusResponse;

public interface QueueAdmissionService {

    QueueStatusResponse enter(Long performanceId, String accessToken);

    QueueStatusResponse status(Long performanceId, String queueSessionId);

    void leave(Long performanceId, String queueSessionId);
}
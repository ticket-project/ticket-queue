package com.ticket.queue.controller;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ticket.queue.controller.response.QueueStatusResponse;
import com.ticket.queue.service.QueueAdmissionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class QueueAdmissionControllerTest {

    private QueueAdmissionService queueAdmissionService;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        queueAdmissionService = mock(QueueAdmissionService.class);
        mockMvc = MockMvcBuilders.standaloneSetup(new QueueAdmissionController(queueAdmissionService))
                .build();
    }

    @Test
    void enter는_bearer_token을_한번_검증하도록_service에_전달한다() throws Exception {
        when(queueAdmissionService.enter(1L, "access-token"))
                .thenReturn(QueueStatusResponse.waiting("session-1", 10L, 1L, 2L));

        mockMvc.perform(post("/api/v1/queue/performances/{performanceId}/enter", 1L)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer access-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("WAITING"))
                .andExpect(jsonPath("$.queueSessionId").value("session-1"))
                .andExpect(jsonPath("$.position").value(10L));

        verify(queueAdmissionService).enter(1L, "access-token");
    }

    @Test
    void status는_access_token없이_queue_session으로_조회한다() throws Exception {
        when(queueAdmissionService.status(1L, "session-1"))
                .thenReturn(QueueStatusResponse.active("session-1", "admission-token", "/booking/seat?performanceId=1"));

        mockMvc.perform(get("/api/v1/queue/performances/{performanceId}/status", 1L)
                        .header("X-Queue-Session", "session-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.admissionToken").value("admission-token"))
                .andExpect(jsonPath("$.redirectUrl").value("/booking/seat?performanceId=1"));

        verify(queueAdmissionService).status(1L, "session-1");
    }

    @Test
    void leave는_queue_session으로_대기열을_나간다() throws Exception {
        mockMvc.perform(post("/api/v1/queue/performances/{performanceId}/leave", 1L)
                        .header("X-Queue-Session", "session-1"))
                .andExpect(status().isNoContent());

        verify(queueAdmissionService).leave(1L, "session-1");
    }

    @Test
    void enter에_bearer_token이_없으면_401을_반환한다() throws Exception {
        mockMvc.perform(post("/api/v1/queue/performances/{performanceId}/enter", 1L))
                .andExpect(status().isUnauthorized());
    }
}
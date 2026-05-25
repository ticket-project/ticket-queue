package com.ticket.queue.api;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ticket.queue.application.QueueAdmissionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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
    void enter는_authorization_헤더를_전달해_대기열에_진입한다() throws Exception {
        when(queueAdmissionService.enter(1L, "Bearer access-token"))
                .thenReturn(QueueStatusResponse.entered("session-1"));

        mockMvc.perform(post("/api/v1/queue/performances/{performanceId}/enter", 1L)
                        .header("Authorization", "Bearer access-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("WAITING"))
                .andExpect(jsonPath("$.queueSessionId").value("session-1"))
                .andExpect(jsonPath("$.position").value(nullValue()))
                .andExpect(jsonPath("$.estimatedWaitSeconds").value(nullValue()))
                .andExpect(jsonPath("$.pollAfterSeconds").value(nullValue()));

        verify(queueAdmissionService).enter(1L, "Bearer access-token");
    }

    @Test
    void enter는_authorization_헤더가_없으면_401을_반환한다() throws Exception {
        mockMvc.perform(post("/api/v1/queue/performances/{performanceId}/enter", 1L))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void status는_queue_session으로_조회한다() throws Exception {
        when(queueAdmissionService.status(1L, "session-1"))
                .thenReturn(QueueStatusResponse.active(
                        "session-1",
                        "admission-token",
                        "/booking/seat?performanceId=1"
                ));

        mockMvc.perform(get("/api/v1/queue/performances/{performanceId}/status", 1L)
                        .header("X-Queue-Session", "session-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.admissionToken").value("admission-token"))
                .andExpect(jsonPath("$.redirectUrl").value("/booking/seat?performanceId=1"));

        verify(queueAdmissionService).status(1L, "session-1");
    }

    @Test
    void leave_api는_제공하지_않는다() throws Exception {
        mockMvc.perform(post("/api/v1/queue/performances/{performanceId}/leave", 1L)
                        .header("X-Queue-Session", "session-1"))
                .andExpect(status().isNotFound());
    }
}

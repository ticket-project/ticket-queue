package com.ticket.queue.api;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ticket.queue.application.QueueAdmissionService;
import com.ticket.support.passport.Passport;
import com.ticket.support.passport.web.InternalAuthPassportFilter;
import com.ticket.support.passport.web.InternalAuthPassportVerifier;
import com.ticket.support.passport.web.PassportArgumentResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class QueueAdmissionControllerTest {

    private static final String INTERNAL_AUTH_HEADER = "X-Internal-Auth";

    private QueueAdmissionService queueAdmissionService;
    private InternalAuthPassportVerifier internalAuthVerifier;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        queueAdmissionService = mock(QueueAdmissionService.class);
        internalAuthVerifier = mock(InternalAuthPassportVerifier.class);
        mockMvc = MockMvcBuilders.standaloneSetup(new QueueAdmissionController(queueAdmissionService))
                .setCustomArgumentResolvers(new PassportArgumentResolver())
                .addFilters(new InternalAuthPassportFilter(internalAuthVerifier))
                .build();
    }

    @Test
    void enter는_internal_auth_헤더를_Passport로_변환해_대기열에_진입한다() throws Exception {
        Passport passport = new Passport(10L, "MEMBER");
        when(internalAuthVerifier.verify("Bearer internal-token")).thenReturn(passport);
        when(queueAdmissionService.enter(1L, passport))
                .thenReturn(QueueStatusResponse.entered("session-1"));

        mockMvc.perform(post("/api/v1/queue/performances/{performanceId}/enter", 1L)
                        .header(INTERNAL_AUTH_HEADER, "Bearer internal-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("WAITING"))
                .andExpect(jsonPath("$.queueSessionId").value("session-1"))
                .andExpect(jsonPath("$.position").value(nullValue()))
                .andExpect(jsonPath("$.estimatedWaitSeconds").value(nullValue()))
                .andExpect(jsonPath("$.pollAfterSeconds").value(nullValue()));

        verify(internalAuthVerifier).verify("Bearer internal-token");
        verify(queueAdmissionService).enter(1L, passport);
    }

    @Test
    void enter는_internal_auth_헤더가_없으면_401을_반환한다() throws Exception {
        mockMvc.perform(post("/api/v1/queue/performances/{performanceId}/enter", 1L))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(internalAuthVerifier);
        verifyNoInteractions(queueAdmissionService);
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

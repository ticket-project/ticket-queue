package com.ticket.queue.api;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ticket.queue.application.PolicySnapshotService;
import com.ticket.queue.domain.PolicyMode;
import com.ticket.support.passport.Passport;
import com.ticket.support.passport.web.PassportAuthenticationFilter;
import com.ticket.support.passport.web.PassportVerifier;
import com.ticket.support.passport.web.PassportArgumentResolver;
import java.time.Duration;
import java.time.LocalDateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class PolicySnapshotControllerTest {

    private static final String INTERNAL_AUTH_HEADER = "X-Internal-Auth";

    private PassportVerifier internalAuthVerifier;
    private PolicySnapshotService policySnapshotService;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        internalAuthVerifier = org.mockito.Mockito.mock(PassportVerifier.class);
        policySnapshotService = org.mockito.Mockito.mock(PolicySnapshotService.class);
        mockMvc = MockMvcBuilders.standaloneSetup(
                        new PolicySnapshotController(policySnapshotService)
                )
                .setCustomArgumentResolvers(new PassportArgumentResolver())
                .addFilters(new PassportAuthenticationFilter(internalAuthVerifier))
                .build();
    }

    @Test
    void Passport를_받은_뒤_회차별_policy_snapshot을_저장한다() throws Exception {
        when(internalAuthVerifier.verify("Bearer internal-token"))
                .thenReturn(new Passport(1L, "SYSTEM"));

        mockMvc.perform(put("/internal/v1/queue/performances/{performanceId}/policy", 1L)
                        .header(INTERNAL_AUTH_HEADER, "Bearer internal-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "admitLimitPerTick": 10,
                                  "maxActiveUsers": 200,
                                  "activeTtlSeconds": 180,
                                  "sessionTtlSeconds": 1800,
                                  "queueMode": "AUTO",
                                  "preopenQueueStartAt": "2026-05-24T19:50:00",
                                  "orderCloseTime": "2026-05-24T21:00:00"
                                }
                                """))
                .andExpect(status().isNoContent());

        verify(internalAuthVerifier).verify("Bearer internal-token");
        verify(policySnapshotService).save(
                1L,
                10,
                200,
                Duration.ofSeconds(180),
                Duration.ofSeconds(1800),
                PolicyMode.AUTO,
                LocalDateTime.of(2026, 5, 24, 19, 50),
                LocalDateTime.of(2026, 5, 24, 21, 0)
        );
    }
}

package com.ticket.queue.api;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ticket.queue.application.AdmissionService;
import com.ticket.support.passport.Passport;
import com.ticket.support.passport.web.PassportArgumentResolver;
import com.ticket.support.passport.web.PassportAuthenticationFilter;
import com.ticket.support.passport.web.PassportVerifier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class AdmissionControllerTest {

    private static final String INTERNAL_AUTH_HEADER = "X-Internal-Auth";
    private static final String QUEUE_TOKEN_HEADER = "X-Queue-Token";

    private AdmissionService admissionService;
    private PassportVerifier internalAuthVerifier;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        admissionService = mock(AdmissionService.class);
        internalAuthVerifier = mock(PassportVerifier.class);
        mockMvc = MockMvcBuilders.standaloneSetup(new AdmissionController(admissionService))
                .setCustomArgumentResolvers(new PassportArgumentResolver())
                .addFilters(new PassportAuthenticationFilter(internalAuthVerifier))
                .build();
    }

    @Test
    void join_uses_internal_auth_passport_and_returns_queue_token() throws Exception {
        Passport passport = new Passport(10L, "MEMBER");
        when(internalAuthVerifier.verify("Bearer internal-token")).thenReturn(passport);
        when(admissionService.join(1L, passport))
                .thenReturn(new JoinResponse(1L, "queue-1", 42L, "WAITING", "queue-token"));

        mockMvc.perform(post("/api/v1/queue/performances/{performanceId}/join", 1L)
                        .header(INTERNAL_AUTH_HEADER, "Bearer internal-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.performanceId").value(1))
                .andExpect(jsonPath("$.queueId").value("queue-1"))
                .andExpect(jsonPath("$.seq").value(42))
                .andExpect(jsonPath("$.status").value("WAITING"))
                .andExpect(jsonPath("$.queueToken").value("queue-token"));

        verify(internalAuthVerifier).verify("Bearer internal-token");
        verify(admissionService).join(1L, passport);
    }

    @Test
    void join_without_internal_auth_returns_401() throws Exception {
        mockMvc.perform(post("/api/v1/queue/performances/{performanceId}/join", 1L))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(internalAuthVerifier);
        verifyNoInteractions(admissionService);
    }

    @Test
    void state_is_public_and_returns_shared_queue_state() throws Exception {
        when(admissionService.state(1L))
                .thenReturn(new PublicStateResponse(
                        1L,
                        "OPEN",
                        100L,
                        1_000L,
                        5_000L,
                        1_717_000_000_000L
                ));

        mockMvc.perform(get("/api/v1/queue/performances/{performanceId}/state", 1L))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.performanceId").value(1))
                .andExpect(jsonPath("$.status").value("OPEN"))
                .andExpect(jsonPath("$.admittedUntilSeq").value(100))
                .andExpect(jsonPath("$.tailSeq").value(1000))
                .andExpect(jsonPath("$.refreshAfterMs").value(5000))
                .andExpect(jsonPath("$.serverTimeMillis").value(1_717_000_000_000L));

        verifyNoInteractions(internalAuthVerifier);
        verify(admissionService).state(1L);
    }

    @Test
    void enter_uses_queue_token_header_and_returns_admission_token() throws Exception {
        when(admissionService.enter(1L, "queue-token"))
                .thenReturn(new EnterResponse(
                        "ACTIVE",
                        "admission-token",
                        1_717_000_900_000L,
                        "/booking/seat?performanceId=1"
                ));

        mockMvc.perform(post("/api/v1/queue/performances/{performanceId}/enter", 1L)
                        .header(QUEUE_TOKEN_HEADER, "queue-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.admissionToken").value("admission-token"))
                .andExpect(jsonPath("$.expiresAtMillis").value(1_717_000_900_000L))
                .andExpect(jsonPath("$.redirectUrl").value("/booking/seat?performanceId=1"));

        verifyNoInteractions(internalAuthVerifier);
        verify(admissionService).enter(1L, "queue-token");
    }

    @Test
    void status_api_is_removed() throws Exception {
        mockMvc.perform(get("/api/v1/queue/performances/{performanceId}/status", 1L))
                .andExpect(status().isNotFound());
    }
}

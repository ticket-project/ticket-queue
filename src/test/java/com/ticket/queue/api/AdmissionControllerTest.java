package com.ticket.queue.api;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ticket.queue.api.dto.EnterResponse;
import com.ticket.queue.api.dto.JoinResponse;
import com.ticket.queue.api.dto.PublicStateResponse;
import com.ticket.queue.application.AdmissionService;
import com.ticket.queue.config.AccessTokenAuthenticationFilter;
import com.ticket.queue.config.AccessTokenVerifier;
import com.ticket.queue.config.AuthenticatedMember;
import com.ticket.queue.config.AuthenticatedMemberArgumentResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class AdmissionControllerTest {

    private static final String QUEUE_TOKEN_HEADER = "X-Queue-Token";

    private AdmissionService admissionService;
    private AccessTokenVerifier accessTokenVerifier;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        admissionService = mock(AdmissionService.class);
        accessTokenVerifier = mock(AccessTokenVerifier.class);
        mockMvc = MockMvcBuilders.standaloneSetup(new AdmissionController(admissionService))
                .setCustomArgumentResolvers(new AuthenticatedMemberArgumentResolver())
                .addFilters(new AccessTokenAuthenticationFilter(accessTokenVerifier))
                .build();
    }

    @Test
    void join_uses_access_token_member_and_returns_queue_token() throws Exception {
        AuthenticatedMember member = new AuthenticatedMember(10L, "MEMBER");
        when(accessTokenVerifier.verify("Bearer access-token")).thenReturn(member);
        when(admissionService.join(1L, member))
                .thenReturn(new JoinResponse(
                        1L,
                        "queue-1",
                        "WAITING",
                        "queue-token",
                        42L,
                        17,
                        42L,
                        24_691L,
                        1_234_550L,
                        1_000L
                ));

        mockMvc.perform(post("/api/v1/queue/performances/{performanceId}/join", 1L)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer access-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.performanceId").value(1))
                .andExpect(jsonPath("$.queueId").value("queue-1"))
                .andExpect(jsonPath("$.status").value("WAITING"))
                .andExpect(jsonPath("$.queueToken").value("queue-token"))
                .andExpect(jsonPath("$.seq").value(42))
                .andExpect(jsonPath("$.shardId").value(17))
                .andExpect(jsonPath("$.localSeq").value(42))
                .andExpect(jsonPath("$.slotId").value(24_691))
                .andExpect(jsonPath("$.slotStartMillis").value(1_234_550))
                .andExpect(jsonPath("$.pollAfterMs").value(1_000));

        verify(accessTokenVerifier).verify("Bearer access-token");
        verify(admissionService).join(1L, member);
    }

    @Test
    void join_without_access_token_returns_401() throws Exception {
        mockMvc.perform(post("/api/v1/queue/performances/{performanceId}/join", 1L))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(accessTokenVerifier);
        verifyNoInteractions(admissionService);
    }

    @Test
    void state_is_public_and_returns_shared_queue_state() throws Exception {
        when(admissionService.state(1L))
                .thenReturn(new PublicStateResponse(
                        1L,
                        "OPEN",
                        128,
                        50L,
                        java.util.Map.of(0, 100L, 1, 90L),
                        java.util.Map.of(0, 1_000L, 1, 900L),
                        100L,
                        1_000L,
                        5_000L,
                        1_717_000_000_000L
                ));

        mockMvc.perform(get("/api/v1/queue/performances/{performanceId}/state", 1L))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.performanceId").value(1))
                .andExpect(jsonPath("$.status").value("OPEN"))
                .andExpect(jsonPath("$.shardCount").value(128))
                .andExpect(jsonPath("$.slotSizeMillis").value(50))
                .andExpect(jsonPath("$.serving.0").value(100))
                .andExpect(jsonPath("$.tail.0").value(1000))
                .andExpect(jsonPath("$.admittedUntilSeq").value(100))
                .andExpect(jsonPath("$.tailSeq").value(1000))
                .andExpect(jsonPath("$.refreshAfterMs").value(5000))
                .andExpect(jsonPath("$.serverTimeMillis").value(1_717_000_000_000L));

        verifyNoInteractions(accessTokenVerifier);
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

        verifyNoInteractions(accessTokenVerifier);
        verify(admissionService).enter(1L, "queue-token");
    }

    @Test
    void status_api_is_removed() throws Exception {
        mockMvc.perform(get("/api/v1/queue/performances/{performanceId}/status", 1L))
                .andExpect(status().isNotFound());
    }
}

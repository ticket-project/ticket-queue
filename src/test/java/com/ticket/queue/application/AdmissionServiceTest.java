package com.ticket.queue.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ticket.queue.api.dto.EnterResponse;
import com.ticket.queue.api.dto.JoinResponse;
import com.ticket.queue.api.dto.PublicStateResponse;
import com.ticket.queue.config.AuthenticatedMember;
import com.ticket.queue.config.QueueProperties;
import com.ticket.queue.config.RedirectProperties;
import com.ticket.queue.domain.AdmissionStateStore;
import com.ticket.queue.domain.EnterResult;
import com.ticket.queue.domain.JoinResult;
import com.ticket.queue.domain.PublicState;
import com.ticket.queue.domain.UuidSupplier;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

@ExtendWith(MockitoExtension.class)
class AdmissionServiceTest {

    @Mock
    private AdmissionStateStore admissionStateStore;

    @Mock
    private QueueTokenService queueTokenService;

    @Mock
    private AdmissionTokenIssuer admissionTokenIssuer;

    @Mock
    private UuidSupplier uuidSupplier;

    private AdmissionService service;

    @BeforeEach
    void setUp() {
        QueueProperties queueProperties = new QueueProperties();
        queueProperties.setDefaultRefreshAfterMs(5_000L);
        queueProperties.setDefaultQueueTtl(Duration.ofHours(24));
        queueProperties.setShoppingSessionTtl(Duration.ofMinutes(15));
        queueProperties.setDefaultMaxActiveSessions(5_000);
        RedirectProperties redirectProperties = new RedirectProperties();
        service = new AdmissionService(
                admissionStateStore,
                queueTokenService,
                admissionTokenIssuer,
                redirectProperties,
                queueProperties,
                uuidSupplier
        );
    }

    @Test
    void join_issues_sequence_and_queue_token() {
        AuthenticatedMember member = new AuthenticatedMember(10L, "MEMBER");
        UUID queueUuid = UUID.fromString("00000000-0000-0000-0000-000000000001");
        when(uuidSupplier.get()).thenReturn(queueUuid);
        when(admissionStateStore.joinQueue(eq(1L), anyString(), eq(queueUuid.toString()), eq(Duration.ofHours(24))))
                .thenReturn(new JoinResult(1L, queueUuid.toString(), 42L, true));
        when(queueTokenService.issue(new QueueTokenClaims(1L, queueUuid.toString(), 42L, 10L), Duration.ofHours(24)))
                .thenReturn("queue-token");

        JoinResponse response = service.join(1L, member);

        assertThat(response.performanceId()).isEqualTo(1L);
        assertThat(response.queueId()).isEqualTo(queueUuid.toString());
        assertThat(response.seq()).isEqualTo(42L);
        assertThat(response.status()).isEqualTo("WAITING");
        assertThat(response.queueToken()).isEqualTo("queue-token");
    }

    @Test
    void public_state_does_not_include_user_specific_status() {
        when(admissionStateStore.readPublicState(1L, 5_000L))
                .thenReturn(new PublicState(1L, "OPEN", 100L, 1_000L, 5_000L, 1_717_000_000_000L));

        PublicStateResponse response = service.state(1L);

        assertThat(response.performanceId()).isEqualTo(1L);
        assertThat(response.status()).isEqualTo("OPEN");
        assertThat(response.admittedUntilSeq()).isEqualTo(100L);
        assertThat(response.tailSeq()).isEqualTo(1_000L);
        assertThat(response.refreshAfterMs()).isEqualTo(5_000L);
        assertThat(response.serverTimeMillis()).isEqualTo(1_717_000_000_000L);
    }

    @Test
    void enter_rejects_when_sequence_is_not_admitted_yet() {
        when(queueTokenService.verify("queue-token"))
                .thenReturn(new QueueTokenClaims(1L, "queue-1", 101L, 10L));
        when(admissionTokenIssuer.issue(10L, 1L, "queue-1", Duration.ofMinutes(15)))
                .thenReturn("candidate-admission-token");
        when(admissionStateStore.enterQueue(
                1L,
                "queue-1",
                101L,
                "candidate-admission-token",
                Duration.ofMinutes(15),
                5_000
        )).thenReturn(EnterResult.notAdmitted());

        assertThatExceptionOfType(ResponseStatusException.class)
                .isThrownBy(() -> service.enter(1L, "queue-token"))
                .satisfies(exception -> assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN));
    }

    @Test
    void enter_returns_admission_token_when_sequence_is_admitted() {
        when(queueTokenService.verify("queue-token"))
                .thenReturn(new QueueTokenClaims(1L, "queue-1", 100L, 10L));
        when(admissionTokenIssuer.issue(10L, 1L, "queue-1", Duration.ofMinutes(15)))
                .thenReturn("candidate-admission-token");
        when(admissionStateStore.enterQueue(
                1L,
                "queue-1",
                100L,
                "candidate-admission-token",
                Duration.ofMinutes(15),
                5_000
        )).thenReturn(EnterResult.admitted("admission-token", 1_717_000_900_000L));

        EnterResponse response = service.enter(1L, "queue-token");

        assertThat(response.status()).isEqualTo("ACTIVE");
        assertThat(response.admissionToken()).isEqualTo("admission-token");
        assertThat(response.expiresAtMillis()).isEqualTo(1_717_000_900_000L);
        assertThat(response.redirectUrl()).isEqualTo("/booking/seat?performanceId=1");
    }

    @Test
    void enter_is_idempotent_for_same_queue_id() {
        when(queueTokenService.verify("queue-token"))
                .thenReturn(new QueueTokenClaims(1L, "queue-1", 100L, 10L));
        when(admissionTokenIssuer.issue(10L, 1L, "queue-1", Duration.ofMinutes(15)))
                .thenReturn("candidate-admission-token");
        when(admissionStateStore.enterQueue(
                1L,
                "queue-1",
                100L,
                "candidate-admission-token",
                Duration.ofMinutes(15),
                5_000
        )).thenReturn(EnterResult.admitted("existing-admission-token", 1_717_000_900_000L));

        EnterResponse response = service.enter(1L, "queue-token");

        assertThat(response.admissionToken()).isEqualTo("existing-admission-token");
    }

    @Test
    void enter_returns_429_when_active_sessions_are_full() {
        when(queueTokenService.verify("queue-token"))
                .thenReturn(new QueueTokenClaims(1L, "queue-1", 100L, 10L));
        when(admissionTokenIssuer.issue(10L, 1L, "queue-1", Duration.ofMinutes(15)))
                .thenReturn("candidate-admission-token");
        when(admissionStateStore.enterQueue(
                1L,
                "queue-1",
                100L,
                "candidate-admission-token",
                Duration.ofMinutes(15),
                5_000
        )).thenReturn(EnterResult.full());

        assertThatExceptionOfType(ResponseStatusException.class)
                .isThrownBy(() -> service.enter(1L, "queue-token"))
                .satisfies(exception -> assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS));
    }

    @Test
    void enter_returns_401_when_queue_token_is_invalid_or_expired() {
        when(queueTokenService.verify("bad-token")).thenThrow(new QueueTokenException("queue token invalid"));

        assertThatExceptionOfType(ResponseStatusException.class)
                .isThrownBy(() -> service.enter(1L, "bad-token"))
                .satisfies(exception -> assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED));

        verify(admissionTokenIssuer, never()).issue(10L, 1L, "queue-1", Duration.ofMinutes(15));
        verify(admissionStateStore, never()).enterQueue(
                eq(1L),
                eq("queue-1"),
                eq(100L),
                eq("candidate-admission-token"),
                eq(Duration.ofMinutes(15)),
                eq(5_000)
        );
    }
}

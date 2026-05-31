package com.ticket.queue.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ticket.queue.config.QueueRedirectProperties;
import com.ticket.queue.api.QueueStatusResponse;
import com.ticket.queue.domain.QueueEntryStatus;
import com.ticket.queue.domain.QueuePolicy;
import com.ticket.queue.domain.QueueMode;
import com.ticket.queue.domain.QueueSession;
import com.ticket.queue.domain.QueueSessionCreation;
import com.ticket.queue.domain.QueueTicketStore;
import com.ticket.support.passport.Passport;
import java.time.Duration;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

@ExtendWith(MockitoExtension.class)
class QueueAdmissionServiceTest {

    @Mock
    private QueueStatusReader queueStatusReader;

    @Mock
    private QueueTicketStore queueTicketStore;

    @Mock
    private QueuePolicyResolver queuePolicyResolver;

    @Mock
    private QueueAdmissionTokenIssuer queueAdmissionTokenIssuer;

    @Test
    void enter는_Passport의_memberId로_queue_session을_발급하고_대기열에_등록한다() {
        QueueRedirectProperties redirectProperties = new QueueRedirectProperties();
        QueueAdmissionService service = new QueueAdmissionService(
                queueStatusReader,
                queueTicketStore,
                queuePolicyResolver,
                queueAdmissionTokenIssuer,
                redirectProperties,
                new PollingIntervalPolicy()
        );
        Passport passport = new Passport(10L, "MEMBER");
        QueueSession session = new QueueSession("session-1", 1L);
        QueuePolicy queuePolicy = new QueuePolicy(25, 300, Duration.ofMinutes(4), Duration.ofMinutes(30));
        Duration sessionTtl = queuePolicy.sessionTtl();
        when(queuePolicyResolver.resolve(1L)).thenReturn(queuePolicy);
        when(queueTicketStore.createSession(1L, 10L, sessionTtl))
                .thenReturn(new QueueSessionCreation(session, true));

        QueueStatusResponse response = service.enter(1L, passport);

        assertThat(response.status()).isEqualTo("WAITING");
        assertThat(response.queueSessionId()).isEqualTo("session-1");
        assertThat(response.position()).isNull();
        assertThat(response.estimatedWaitSeconds()).isNull();
        assertThat(response.pollAfterSeconds()).isNull();
        verify(queueTicketStore).registerWaiting(1L, "session-1", sessionTtl);
    }

    @Test
    void enter는_이미_발급된_회원_session이_있으면_기존_session을_반환하고_대기열에_다시_등록하지_않는다() {
        QueueRedirectProperties redirectProperties = new QueueRedirectProperties();
        QueueAdmissionService service = new QueueAdmissionService(
                queueStatusReader,
                queueTicketStore,
                queuePolicyResolver,
                queueAdmissionTokenIssuer,
                redirectProperties,
                new PollingIntervalPolicy()
        );
        Passport passport = new Passport(10L, "MEMBER");
        QueueSession session = new QueueSession("session-1", 1L);
        QueuePolicy queuePolicy = new QueuePolicy(25, 300, Duration.ofMinutes(4), Duration.ofMinutes(30));
        Duration sessionTtl = queuePolicy.sessionTtl();
        when(queuePolicyResolver.resolve(1L)).thenReturn(queuePolicy);
        when(queueTicketStore.createSession(1L, 10L, sessionTtl))
                .thenReturn(new QueueSessionCreation(session, false));

        QueueStatusResponse response = service.enter(1L, passport);

        assertThat(response.status()).isEqualTo("WAITING");
        assertThat(response.queueSessionId()).isEqualTo("session-1");
        verify(queueTicketStore, never()).registerWaiting(1L, "session-1", sessionTtl);
    }

    @Test
    void enter는_회차별_snapshot이_대기열_비활성화를_가리키면_session을_발급하지_않는다() {
        QueueRedirectProperties redirectProperties = new QueueRedirectProperties();
        QueueAdmissionService service = new QueueAdmissionService(
                queueStatusReader,
                queueTicketStore,
                queuePolicyResolver,
                queueAdmissionTokenIssuer,
                redirectProperties,
                new PollingIntervalPolicy()
        );
        Passport passport = new Passport(10L, "MEMBER");
        when(queuePolicyResolver.resolve(1L))
                .thenReturn(new QueuePolicy(
                        25,
                        300,
                        Duration.ofMinutes(4),
                        Duration.ofMinutes(30),
                        QueueMode.FORCE_OFF,
                        null,
                        null
                ));

        assertThatExceptionOfType(ResponseStatusException.class)
                .isThrownBy(() -> service.enter(1L, passport))
                .satisfies(exception -> assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN));
        verify(queueTicketStore, never()).createSession(1L, 10L, Duration.ofMinutes(30));
        verify(queueTicketStore, never()).registerWaiting(1L, "session-1", Duration.ofMinutes(30));
    }

    @Test
    void active_상태는_남은_active_ttl로_admission_token과_redirect_url을_반환한다() {
        QueueRedirectProperties redirectProperties = new QueueRedirectProperties();
        QueueAdmissionService service = new QueueAdmissionService(
                queueStatusReader,
                queueTicketStore,
                queuePolicyResolver,
                queueAdmissionTokenIssuer,
                redirectProperties,
                new PollingIntervalPolicy()
        );
        when(queueTicketStore.findSession("session-1")).thenReturn(Optional.of(new QueueSession("session-1", 1L)));
        when(queueStatusReader.read(1L, "session-1"))
                .thenReturn(new QueueStatusReader.Result(
                        QueueEntryStatus.ADMITTED,
                        null,
                        Duration.ofMinutes(3)
                ));
        when(queueAdmissionTokenIssuer.issue(1L, "session-1", Duration.ofMinutes(3)))
                .thenReturn("admission-token");

        QueueStatusResponse response = service.status(1L, "session-1");

        assertThat(response.status()).isEqualTo("ACTIVE");
        assertThat(response.admissionToken()).isEqualTo("admission-token");
        assertThat(response.redirectUrl()).isEqualTo("/booking/seat?performanceId=1");
    }

    @Test
    void waiting_응답은_회차별_정책으로_예상_대기시간을_계산한다() {
        QueueRedirectProperties redirectProperties = new QueueRedirectProperties();
        QueueAdmissionService service = new QueueAdmissionService(
                queueStatusReader,
                queueTicketStore,
                queuePolicyResolver,
                queueAdmissionTokenIssuer,
                redirectProperties,
                new PollingIntervalPolicy()
        );
        when(queueTicketStore.findSession("session-1")).thenReturn(Optional.of(new QueueSession("session-1", 1L)));
        when(queueStatusReader.read(1L, "session-1"))
                .thenReturn(new QueueStatusReader.Result(
                        QueueEntryStatus.WAITING,
                        60L,
                        null
                ));
        when(queuePolicyResolver.resolve(1L))
                .thenReturn(new QueuePolicy(20, 300, Duration.ofMinutes(5), Duration.ofHours(1)));

        QueueStatusResponse response = service.status(1L, "session-1");

        assertThat(response.status()).isEqualTo("WAITING");
        assertThat(response.position()).isEqualTo(60L);
        assertThat(response.estimatedWaitSeconds()).isEqualTo(3L);
    }
}

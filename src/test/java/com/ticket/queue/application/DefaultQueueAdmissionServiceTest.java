package com.ticket.queue.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.ticket.queue.config.QueueAdmissionProperties;
import com.ticket.queue.config.QueueRedirectProperties;
import com.ticket.queue.api.QueueStatusResponse;
import com.ticket.queue.domain.QueueEntryStatus;
import com.ticket.queue.domain.QueueSession;
import com.ticket.queue.domain.QueueSessionCreation;
import com.ticket.queue.domain.QueueTicketStore;
import java.time.Duration;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class DefaultQueueAdmissionServiceTest {

    @Mock
    private EnterQueueUseCase enterQueueUseCase;

    @Mock
    private GetQueueStatusUseCase getQueueStatusUseCase;

    @Mock
    private QueueTicketStore queueTicketStore;

    @Mock
    private QueuePolicyResolver queuePolicyResolver;

    @Mock
    private QueueAdmissionTokenIssuer queueAdmissionTokenIssuer;

    @Test
    void enter는_access_token을_검증한_뒤_queue_session을_발급하고_대기열에_등록한다() {
        QueueAdmissionProperties admissionProperties = new QueueAdmissionProperties();
        QueueRedirectProperties redirectProperties = new QueueRedirectProperties();
        QueueAccessTokenVerifier accessTokenVerifier = org.mockito.Mockito.mock(QueueAccessTokenVerifier.class);
        DefaultQueueAdmissionService service = new DefaultQueueAdmissionService(
                enterQueueUseCase,
                getQueueStatusUseCase,
                queueTicketStore,
                queuePolicyResolver,
                queueAdmissionTokenIssuer,
                accessTokenVerifier,
                admissionProperties,
                redirectProperties,
                new PollingIntervalPolicy()
        );
        QueueSession session = new QueueSession("session-1", 1L);
        Duration sessionTtl = admissionProperties.getSessionTtl();
        when(accessTokenVerifier.verify("Bearer access-token"))
                .thenReturn(new AuthenticatedMember(10L, "USER"));
        when(queueTicketStore.createSession(1L, 10L, sessionTtl))
                .thenReturn(new QueueSessionCreation(session, true));

        QueueStatusResponse response = service.enter(1L, "Bearer access-token");

        assertThat(response.status()).isEqualTo("WAITING");
        assertThat(response.queueSessionId()).isEqualTo("session-1");
        assertThat(response.position()).isNull();
        assertThat(response.estimatedWaitSeconds()).isNull();
        assertThat(response.pollAfterSeconds()).isNull();
        verify(accessTokenVerifier).verify("Bearer access-token");
        verify(enterQueueUseCase).execute(new EnterQueueUseCase.Input(1L, "session-1", sessionTtl));
        verifyNoInteractions(queuePolicyResolver);
    }

    @Test
    void enter는_이미_발급된_회원_session이_있으면_기존_session을_반환하고_대기열에_다시_등록하지_않는다() {
        QueueAdmissionProperties admissionProperties = new QueueAdmissionProperties();
        QueueRedirectProperties redirectProperties = new QueueRedirectProperties();
        QueueAccessTokenVerifier accessTokenVerifier = org.mockito.Mockito.mock(QueueAccessTokenVerifier.class);
        DefaultQueueAdmissionService service = new DefaultQueueAdmissionService(
                enterQueueUseCase,
                getQueueStatusUseCase,
                queueTicketStore,
                queuePolicyResolver,
                queueAdmissionTokenIssuer,
                accessTokenVerifier,
                admissionProperties,
                redirectProperties,
                new PollingIntervalPolicy()
        );
        QueueSession session = new QueueSession("session-1", 1L);
        Duration sessionTtl = admissionProperties.getSessionTtl();
        when(accessTokenVerifier.verify("Bearer access-token"))
                .thenReturn(new AuthenticatedMember(10L, "USER"));
        when(queueTicketStore.createSession(1L, 10L, sessionTtl))
                .thenReturn(new QueueSessionCreation(session, false));

        QueueStatusResponse response = service.enter(1L, "Bearer access-token");

        assertThat(response.status()).isEqualTo("WAITING");
        assertThat(response.queueSessionId()).isEqualTo("session-1");
        verify(accessTokenVerifier).verify("Bearer access-token");
        verifyNoInteractions(enterQueueUseCase);
        verifyNoInteractions(queuePolicyResolver);
    }

    @Test
    void active_상태는_남은_active_ttl로_admission_token과_redirect_url을_반환한다() {
        QueueAdmissionProperties admissionProperties = new QueueAdmissionProperties();
        QueueRedirectProperties redirectProperties = new QueueRedirectProperties();
        DefaultQueueAdmissionService service = new DefaultQueueAdmissionService(
                enterQueueUseCase,
                getQueueStatusUseCase,
                queueTicketStore,
                queuePolicyResolver,
                queueAdmissionTokenIssuer,
                org.mockito.Mockito.mock(QueueAccessTokenVerifier.class),
                admissionProperties,
                redirectProperties,
                new PollingIntervalPolicy()
        );
        when(queueTicketStore.findSession("session-1")).thenReturn(Optional.of(new QueueSession("session-1", 1L)));
        when(getQueueStatusUseCase.execute(new GetQueueStatusUseCase.Input(1L, "session-1")))
                .thenReturn(new GetQueueStatusUseCase.Output(
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
}

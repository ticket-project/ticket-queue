package com.ticket.queue.application;

import com.ticket.queue.config.QueueAdmissionProperties;
import com.ticket.queue.config.QueueRedirectProperties;
import com.ticket.queue.api.QueueStatusResponse;
import com.ticket.queue.domain.QueueEntryStatus;
import com.ticket.queue.domain.QueuePolicy;
import com.ticket.queue.domain.QueueSession;
import com.ticket.queue.domain.QueueSessionCreation;
import com.ticket.queue.domain.QueueTicketStore;
import java.time.Duration;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
@RequiredArgsConstructor
public class DefaultQueueAdmissionService implements QueueAdmissionService {

    private final EnterQueueUseCase enterQueueUseCase;
    private final GetQueueStatusUseCase getQueueStatusUseCase;
    private final QueueTicketStore queueTicketStore;
    private final QueuePolicyResolver queuePolicyResolver;
    private final QueueAdmissionTokenIssuer queueAdmissionTokenIssuer;
    private final QueueAccessTokenVerifier accessTokenVerifier;
    private final QueueAdmissionProperties queueAdmissionProperties;
    private final QueueRedirectProperties queueRedirectProperties;
    private final PollingIntervalPolicy pollingIntervalPolicy;

    @Override
    public QueueStatusResponse enter(final Long performanceId, final String authorizationHeader) {
        AuthenticatedMember member = accessTokenVerifier.verify(authorizationHeader);

        QueueSessionCreation creation = queueTicketStore.createSession(
                performanceId,
                member.memberId(),
                queueAdmissionProperties.getSessionTtl()
        );
        QueueSession session = creation.session();
        if (creation.created()) {
            enterQueueUseCase.execute(
                    new EnterQueueUseCase.Input(
                            performanceId,
                            session.queueSessionId(),
                            queueAdmissionProperties.getSessionTtl()
                    )
            );
        }
        return QueueStatusResponse.entered(session.queueSessionId());
    }

    @Override
    public QueueStatusResponse status(final Long performanceId, final String queueSessionId) {
        QueueSession session = findSession(queueSessionId);
        assertSamePerformance(performanceId, session.performanceId());

        GetQueueStatusUseCase.Output output = getQueueStatusUseCase.execute(
                new GetQueueStatusUseCase.Input(session.performanceId(), session.queueSessionId())
        );
        return toResponse(session, output.status(), output.position(), output.activeTtl());
    }

    private QueueStatusResponse toResponse(
            final QueueSession session,
            final QueueEntryStatus status,
            final Long position,
            final Duration activeTtl
    ) {
        return switch (status) {
            case WAITING -> waitingResponse(session, position);
            case ADMITTED -> activeResponse(session, activeTtl);
            case EXPIRED -> QueueStatusResponse.expired(session.queueSessionId());
        };
    }

    private QueueStatusResponse waitingResponse(final QueueSession session, final Long position) {
        QueuePolicy policy = queuePolicyResolver.resolve();
        long normalizedPosition = position == null ? 1L : position;
        return QueueStatusResponse.waiting(
                session.queueSessionId(),
                normalizedPosition,
                policy.estimateWaitSeconds(normalizedPosition),
                pollingIntervalPolicy.pollAfterSeconds(normalizedPosition)
        );
    }

    private QueueStatusResponse activeResponse(final QueueSession session, final Duration activeTtl) {
        return QueueStatusResponse.active(
                session.queueSessionId(),
                queueAdmissionTokenIssuer.issue(session.performanceId(), session.queueSessionId(), activeTtl),
                queueRedirectProperties.resolve(session.performanceId())
        );
    }

    private QueueSession findSession(final String queueSessionId) {
        return queueTicketStore.findSession(queueSessionId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.GONE, "queue session expired"));
    }

    private void assertSamePerformance(final Long requestedPerformanceId, final Long sessionPerformanceId) {
        if (!sessionPerformanceId.equals(requestedPerformanceId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "queue session performance mismatch");
        }
    }
}

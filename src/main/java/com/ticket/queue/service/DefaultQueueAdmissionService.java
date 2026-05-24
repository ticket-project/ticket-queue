package com.ticket.queue.service;

import com.ticket.queue.config.QueueAdmissionProperties;
import com.ticket.queue.config.QueueRedirectProperties;
import com.ticket.queue.controller.response.QueueStatusResponse;
import com.ticket.queue.domain.command.enter.EnterQueueUseCase;
import com.ticket.queue.domain.command.exit.ExitQueueUseCase;
import com.ticket.queue.domain.model.QueueEntryStatus;
import com.ticket.queue.domain.model.QueuePolicy;
import com.ticket.queue.domain.model.QueueSession;
import com.ticket.queue.domain.port.QueueTicketStore;
import com.ticket.queue.domain.query.status.GetQueueStatusUseCase;
import com.ticket.queue.domain.service.QueuePolicyResolver;
import com.ticket.queue.service.policy.PollingIntervalPolicy;
import com.ticket.support.security.admission.AdmissionTokenService;
import com.ticket.support.security.jwt.JwtMemberClaims;
import com.ticket.support.security.jwt.JwtTokenVerifier;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
@RequiredArgsConstructor
public class DefaultQueueAdmissionService implements QueueAdmissionService {

    private final JwtTokenVerifier jwtTokenVerifier;
    private final AdmissionTokenService admissionTokenService;
    private final EnterQueueUseCase enterQueueUseCase;
    private final GetQueueStatusUseCase getQueueStatusUseCase;
    private final ExitQueueUseCase exitQueueUseCase;
    private final QueueTicketStore queueTicketStore;
    private final QueuePolicyResolver queuePolicyResolver;
    private final QueueAdmissionProperties queueAdmissionProperties;
    private final QueueRedirectProperties queueRedirectProperties;
    private final PollingIntervalPolicy pollingIntervalPolicy;

    @Override
    public QueueStatusResponse enter(final Long performanceId, final String accessToken) {
        JwtMemberClaims claims = verifyAccessToken(accessToken);
        EnterQueueUseCase.Output output = enterQueueUseCase.execute(
                new EnterQueueUseCase.Input(performanceId, claims.memberId())
        );
        QueueSession session = queueTicketStore.createSession(
                performanceId,
                claims.memberId(),
                queueAdmissionProperties.getSessionTtl()
        );
        return toResponse(session, output.status(), output.position());
    }

    @Override
    public QueueStatusResponse status(final Long performanceId, final String queueSessionId) {
        QueueSession session = findSession(queueSessionId);
        assertSamePerformance(performanceId, session.performanceId());

        GetQueueStatusUseCase.Output output = getQueueStatusUseCase.execute(
                new GetQueueStatusUseCase.Input(session.performanceId(), session.memberId())
        );
        return toResponse(session, output.status(), output.position());
    }

    @Override
    public void leave(final Long performanceId, final String queueSessionId) {
        QueueSession session = findSession(queueSessionId);
        assertSamePerformance(performanceId, session.performanceId());
        exitQueueUseCase.execute(new ExitQueueUseCase.Input(session.performanceId(), session.memberId()));
        queueTicketStore.deleteSession(queueSessionId);
    }

    private QueueStatusResponse toResponse(
            final QueueSession session,
            final QueueEntryStatus status,
            final Long position
    ) {
        return switch (status) {
            case WAITING -> waitingResponse(session, position);
            case ADMITTED -> activeResponse(session);
            case EXPIRED, LEFT -> QueueStatusResponse.expired(session.queueSessionId());
        };
    }

    private QueueStatusResponse waitingResponse(final QueueSession session, final Long position) {
        QueuePolicy policy = queuePolicyResolver.resolve(session.performanceId());
        long normalizedPosition = position == null ? 1L : position;
        return QueueStatusResponse.waiting(
                session.queueSessionId(),
                normalizedPosition,
                policy.estimateWaitSeconds(normalizedPosition),
                pollingIntervalPolicy.pollAfterSeconds(normalizedPosition)
        );
    }

    private QueueStatusResponse activeResponse(final QueueSession session) {
        String admissionToken = admissionTokenService.issue(session.memberId(), session.performanceId());
        return QueueStatusResponse.active(
                session.queueSessionId(),
                admissionToken,
                queueRedirectProperties.resolve(session.performanceId())
        );
    }

    private QueueSession findSession(final String queueSessionId) {
        return queueTicketStore.findSession(queueSessionId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.GONE, "queue session expired"));
    }

    private JwtMemberClaims verifyAccessToken(final String accessToken) {
        try {
            return jwtTokenVerifier.verify(accessToken);
        } catch (RuntimeException exception) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "access token invalid", exception);
        }
    }

    private void assertSamePerformance(final Long requestedPerformanceId, final Long sessionPerformanceId) {
        if (!sessionPerformanceId.equals(requestedPerformanceId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "queue session performance mismatch");
        }
    }
}
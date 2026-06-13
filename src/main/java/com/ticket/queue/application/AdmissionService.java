package com.ticket.queue.application;

import com.ticket.queue.api.EnterResponse;
import com.ticket.queue.api.JoinResponse;
import com.ticket.queue.api.PublicStateResponse;
import com.ticket.queue.config.QueueProperties;
import com.ticket.queue.config.RedirectProperties;
import com.ticket.queue.domain.JoinResult;
import com.ticket.queue.domain.Policy;
import com.ticket.queue.domain.EnterResult;
import com.ticket.queue.domain.PublicState;
import com.ticket.queue.domain.AdmissionStateStore;
import com.ticket.queue.domain.UuidSupplier;
import com.ticket.support.passport.Passport;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.HexFormat;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
@RequiredArgsConstructor
public class AdmissionService {

    private static final String STATUS_WAITING = "WAITING";
    private static final String STATUS_ACTIVE = "ACTIVE";

    private final AdmissionStateStore admissionStateStore;
    private final PolicyResolver policyResolver;
    private final QueueTokenService queueTokenService;
    private final AdmissionTokenIssuer queueAdmissionTokenIssuer;
    private final RedirectProperties redirectProperties;
    private final QueueProperties queueProperties;
    private final UuidSupplier uuidSupplier;

    public JoinResponse join(final Long performanceId, final Passport passport) {
        Policy policy = policyResolver.resolve(performanceId);
        if (!policy.requiresQueueAt(LocalDateTime.now())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "queue is not required");
        }

        String candidateQueueId = uuidSupplier.get().toString();
        JoinResult join = admissionStateStore.joinQueue(
                performanceId,
                userIdHash(passport),
                candidateQueueId,
                policy.sessionTtl(),
                queueProperties.getDefaultRefreshAfterMs()
        );
        String queueToken = queueTokenService.issue(
                new QueueTokenClaims(performanceId, join.queueId(), join.seq()),
                policy.sessionTtl()
        );

        return new JoinResponse(performanceId, join.queueId(), join.seq(), STATUS_WAITING, queueToken);
    }

    public PublicStateResponse state(final Long performanceId) {
        PublicState state = admissionStateStore.readPublicState(
                performanceId,
                queueProperties.getDefaultRefreshAfterMs()
        );
        return new PublicStateResponse(
                state.performanceId(),
                state.status(),
                state.admittedUntilSeq(),
                state.tailSeq(),
                state.refreshAfterMs(),
                state.serverTimeMillis()
        );
    }

    public EnterResponse enter(final Long performanceId, final String queueToken) {
        QueueTokenClaims claims = verifyQueueToken(queueToken);
        if (!performanceId.equals(claims.performanceId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "queue token performance mismatch");
        }

        Policy policy = policyResolver.resolve(performanceId);
        String admissionToken = queueAdmissionTokenIssuer.issue(
                performanceId,
                claims.queueId(),
                policy.activeTtl()
        );
        EnterResult result = admissionStateStore.enterQueue(
                performanceId,
                claims.queueId(),
                claims.seq(),
                admissionToken,
                policy.activeTtl(),
                policy.maxActiveUsers()
        );

        return switch (result.status()) {
            case ADMITTED -> new EnterResponse(
                    STATUS_ACTIVE,
                    result.admissionToken(),
                    result.expiresAtMillis(),
                    redirectProperties.resolve(performanceId)
            );
            case NOT_ADMITTED -> throw new ResponseStatusException(HttpStatus.FORBIDDEN, "queue sequence not admitted");
            case FULL -> throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "active sessions are full");
            case EXPIRED -> throw new ResponseStatusException(HttpStatus.GONE, "queue entry expired");
        };
    }

    private QueueTokenClaims verifyQueueToken(final String queueToken) {
        try {
            return queueTokenService.verify(queueToken);
        } catch (QueueTokenException exception) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, exception.getMessage(), exception);
        }
    }

    private String userIdHash(final Passport passport) {
        if (passport == null || passport.memberId() == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "authenticated passport is required");
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashed = digest.digest(String.valueOf(passport.memberId()).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hashed);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is not available", exception);
        }
    }
}

package com.ticket.queue.application;

import com.ticket.queue.api.dto.EnterResponse;
import com.ticket.queue.api.dto.JoinResponse;
import com.ticket.queue.api.dto.PublicStateResponse;
import com.ticket.queue.config.QueueProperties;
import com.ticket.queue.config.RedirectProperties;
import com.ticket.queue.config.AuthenticatedMember;
import com.ticket.queue.domain.AdmissionStateStore;
import com.ticket.queue.domain.EnterResult;
import com.ticket.queue.domain.JoinResult;
import com.ticket.queue.domain.PublicState;
import com.ticket.queue.domain.UuidSupplier;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
@RequiredArgsConstructor
public class AdmissionService {

    private final AdmissionStateStore admissionStateStore;
    private final QueueTokenService queueTokenService;
    private final AdmissionTokenIssuer admissionTokenIssuer;
    private final RedirectProperties redirectProperties;
    private final QueueProperties queueProperties;
    private final UuidSupplier uuidSupplier;

    public JoinResponse join(final Long performanceId, final AuthenticatedMember member) {
        JoinResult join = joinState(performanceId, member);
        String queueToken = issueQueueToken(performanceId, join, member);

        return JoinResponse.waiting(performanceId, join, queueToken);
    }

    public PublicStateResponse state(final Long performanceId) {
        return PublicStateResponse.from(readPublicState(performanceId));
    }

    public EnterResponse enter(final Long performanceId, final String queueToken) {
        QueueTokenClaims claims = verifyQueueToken(queueToken);
        verifyPerformance(performanceId, claims);

        String admissionToken = issueAdmissionToken(performanceId, claims);
        EnterResult result = enterState(performanceId, claims, admissionToken);

        return switch (result.status()) {
            case ADMITTED -> EnterResponse.active(
                    result.admissionToken(),
                    result.expiresAtMillis(),
                    redirectProperties.resolve(performanceId)
            );
            case NOT_ADMITTED -> throw new ResponseStatusException(HttpStatus.FORBIDDEN, "queue sequence not admitted");
            case FULL -> throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "active sessions are full");
            case EXPIRED -> throw new ResponseStatusException(HttpStatus.GONE, "queue entry expired");
        };
    }

    private JoinResult joinState(
            final Long performanceId,
            final AuthenticatedMember member
    ) {
        return admissionStateStore.joinQueue(
                performanceId,
                userIdHash(member),
                uuidSupplier.get().toString(),
                queueProperties.getDefaultQueueTtl()
        );
    }

    private String issueQueueToken(
            final Long performanceId,
            final JoinResult join,
            final AuthenticatedMember member
    ) {
        return queueTokenService.issue(
                new QueueTokenClaims(performanceId, join.queueId(), join.seq(), member.memberId()),
                queueProperties.getDefaultQueueTtl()
        );
    }

    private PublicState readPublicState(final Long performanceId) {
        return admissionStateStore.readPublicState(
                performanceId,
                queueProperties.getDefaultRefreshAfterMs()
        );
    }

    private void verifyPerformance(
            final Long performanceId,
            final QueueTokenClaims claims
    ) {
        if (!performanceId.equals(claims.performanceId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "queue token performance mismatch");
        }
    }

    private String issueAdmissionToken(
            final Long performanceId,
            final QueueTokenClaims claims
    ) {
        return admissionTokenIssuer.issue(
                claims.memberId(),
                performanceId,
                claims.queueId(),
                queueProperties.getShoppingSessionTtl()
        );
    }

    private EnterResult enterState(
            final Long performanceId,
            final QueueTokenClaims claims,
            final String admissionToken
    ) {
        return admissionStateStore.enterQueue(
                performanceId,
                claims.queueId(),
                claims.seq(),
                admissionToken,
                queueProperties.getShoppingSessionTtl(),
                queueProperties.getDefaultMaxActiveSessions()
        );
    }

    private QueueTokenClaims verifyQueueToken(final String queueToken) {
        try {
            return queueTokenService.verify(queueToken);
        } catch (QueueTokenException exception) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, exception.getMessage(), exception);
        }
    }

    private String userIdHash(final AuthenticatedMember member) {
        if (member == null || member.memberId() == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "authenticated member is required");
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashed = digest.digest(String.valueOf(member.memberId()).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hashed);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is not available", exception);
        }
    }
}

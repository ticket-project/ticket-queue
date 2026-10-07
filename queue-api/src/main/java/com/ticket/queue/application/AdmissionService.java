package com.ticket.queue.application;

import com.ticket.queue.api.dto.EnterResponse;
import com.ticket.queue.api.dto.JoinResponse;
import com.ticket.queue.api.dto.PublicStateResponse;
import com.ticket.queue.config.QueueProperties;
import com.ticket.queue.config.RedirectProperties;
import com.ticket.queue.config.AuthenticatedMember;
import com.ticket.queue.domain.EnterResult;
import com.ticket.queue.domain.JoinResult;
import com.ticket.queue.domain.QueueShardSlot;
import com.ticket.queue.infra.RedisAdmissionStateStore;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
@RequiredArgsConstructor
public class AdmissionService {

    private final RedisAdmissionStateStore admissionStateStore;
    private final QueueTokenService queueTokenService;
    private final AdmissionTokenIssuer admissionTokenIssuer;
    private final RedirectProperties redirectProperties;
    private final QueueProperties queueProperties;
    private final QueueShardSlotCalculator queueShardSlotCalculator;

    public JoinResponse join(final Long performanceId, final AuthenticatedMember member) {
        String userIdHash = userIdHash(member);
        QueueShardSlot shardSlot = queueShardSlotCalculator.calculate(performanceId, member.memberId());
        JoinResult join = admissionStateStore.joinQueue(
                performanceId,
                userIdHash,
                UUID.randomUUID().toString(),
                shardSlot,
                queueProperties.getDefaultQueueTtl()
        );
        String queueToken = queueTokenService.issue(
                new QueueTokenClaims(
                        performanceId,
                        join.queueId(),
                        join.shardId(),
                        join.localSeq(),
                        join.slotId(),
                        member.memberId()
                ),
                queueProperties.getDefaultQueueTtl()
        );

        return JoinResponse.waiting(performanceId, join, queueToken, queueProperties.getJoinPollAfterMs());
    }

    public PublicStateResponse state(final Long performanceId) {
        return PublicStateResponse.from(
                admissionStateStore.readPublicState(performanceId, queueProperties.getDefaultRefreshAfterMs())
        );
    }

    public EnterResponse enter(final Long performanceId, final String queueToken) {
        QueueTokenClaims claims = verifyQueueToken(queueToken);
        verifyPerformance(performanceId, claims);

        String admissionToken = admissionTokenIssuer.issue(
                claims.memberId(),
                performanceId,
                claims.queueId(),
                queueProperties.getShoppingSessionTtl()
        );
        EnterResult result = admissionStateStore.enterQueue(
                performanceId,
                claims.queueId(),
                claims.shardId(),
                claims.localSeq(),
                admissionToken,
                queueProperties.getShoppingSessionTtl()
        );

        return switch (result.status()) {
            case ADMITTED -> EnterResponse.active(
                    result.admissionToken(),
                    result.expiresAtMillis(),
                    redirectProperties.resolve(performanceId)
            );
            case NOT_ADMITTED -> throw new ResponseStatusException(HttpStatus.FORBIDDEN, "queue sequence not admitted");
            case EXPIRED -> throw new ResponseStatusException(HttpStatus.GONE, "queue entry expired");
        };
    }

    private void verifyPerformance(
            final Long performanceId,
            final QueueTokenClaims claims
    ) {
        if (!performanceId.equals(claims.performanceId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "queue token performance mismatch");
        }
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

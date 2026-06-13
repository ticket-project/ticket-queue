package com.ticket.queue.infra;

import com.ticket.queue.domain.PolicyMode;
import com.ticket.queue.domain.Policy;
import com.ticket.queue.domain.PolicyStore;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.redisson.api.RBucket;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class RedisPolicyStore implements PolicyStore {

    private static final String SEPARATOR = "\\|";
    private static final String ENCODED_SEPARATOR = "|";

    private final RedissonClient redissonClient;

    @Override
    public void save(final Long performanceId, final Policy policy) {
        validatePositive(performanceId, "performanceId");
        if (policy == null) {
            throw new IllegalArgumentException("policy must not be null");
        }
        policyBucket(performanceId).set(encode(policy));
    }

    @Override
    public Optional<Policy> findByPerformanceId(final Long performanceId) {
        if (performanceId == null || performanceId <= 0) {
            return Optional.empty();
        }
        String encoded = policyBucket(performanceId).get();
        if (encoded == null || encoded.isBlank()) {
            return Optional.empty();
        }
        return Optional.of(decode(encoded));
    }

    private String encode(final Policy policy) {
        return policy.admitLimitPerTick()
                + ENCODED_SEPARATOR
                + policy.maxActiveUsers()
                + ENCODED_SEPARATOR
                + policy.activeTtl().toMillis()
                + ENCODED_SEPARATOR
                + policy.sessionTtl().toMillis()
                + ENCODED_SEPARATOR
                + policy.queueMode()
                + ENCODED_SEPARATOR
                + encodeDateTime(policy.preopenQueueStartAt())
                + ENCODED_SEPARATOR
                + encodeDateTime(policy.orderCloseTime());
    }

    private Policy decode(final String encoded) {
        String[] tokens = encoded.split(SEPARATOR, -1);
        if (tokens.length == 4) {
            return new Policy(
                    Integer.parseInt(tokens[0]),
                    Integer.parseInt(tokens[1]),
                    Duration.ofMillis(Long.parseLong(tokens[2])),
                    Duration.ofMillis(Long.parseLong(tokens[3]))
            );
        }
        if (tokens.length != 7) {
            throw new IllegalStateException("invalid queue policy snapshot");
        }
        return new Policy(
                Integer.parseInt(tokens[0]),
                Integer.parseInt(tokens[1]),
                Duration.ofMillis(Long.parseLong(tokens[2])),
                Duration.ofMillis(Long.parseLong(tokens[3])),
                decodePolicyMode(tokens[4]),
                decodeDateTime(tokens[5]),
                decodeDateTime(tokens[6])
        );
    }

    private String encodeDateTime(final LocalDateTime value) {
        return value == null ? "" : value.toString();
    }

    private PolicyMode decodePolicyMode(final String value) {
        return value == null || value.isBlank() ? PolicyMode.FORCE_ON : PolicyMode.valueOf(value);
    }

    private LocalDateTime decodeDateTime(final String value) {
        return value == null || value.isBlank() ? null : LocalDateTime.parse(value);
    }

    private RBucket<String> policyBucket(final Long performanceId) {
        return redissonClient.getBucket(RedisKey.policy(performanceId), StringCodec.INSTANCE);
    }

    private void validatePositive(final Long value, final String name) {
        if (value == null || value <= 0) {
            throw new IllegalArgumentException(name + " must be positive");
        }
    }
}

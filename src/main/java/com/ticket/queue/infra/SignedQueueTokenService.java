package com.ticket.queue.infra;

import com.ticket.queue.application.QueueTokenClaims;
import com.ticket.queue.application.QueueTokenException;
import com.ticket.queue.application.QueueTokenService;
import com.ticket.queue.config.QueueProperties;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Objects;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class SignedQueueTokenService implements QueueTokenService {

    private static final String VERSION = "q2";
    private static final String DELIMITER = ".";
    private static final String HMAC_ALGORITHM = "HmacSHA256";
    private static final Base64.Encoder BASE64_URL_ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder BASE64_URL_DECODER = Base64.getUrlDecoder();

    private final Clock clock;
    private final byte[] secretKey;

    @Autowired
    public SignedQueueTokenService(final QueueProperties properties) {
        this(properties, Clock.systemUTC());
    }

    SignedQueueTokenService(final QueueProperties properties, final Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        String secret = validateSecret(properties);
        this.secretKey = secret.getBytes(StandardCharsets.UTF_8);
    }

    @Override
    public String issue(final QueueTokenClaims claims, final Duration ttl) {
        validateClaims(claims);
        validateTtl(ttl);

        Instant expiresAt = clock.instant().plus(ttl);
        return buildToken(claims, expiresAt);
    }

    @Override
    public QueueTokenClaims verify(final String token) {
        return parse(token);
    }

    private String buildToken(final QueueTokenClaims claims, final Instant expiresAt) {
        String signingInput = String.join(
                DELIMITER,
                VERSION,
                Long.toString(claims.performanceId()),
                encodeQueueId(claims.queueId()),
                Integer.toString(claims.shardId()),
                Long.toString(claims.localSeq()),
                Long.toString(claims.slotId()),
                Long.toString(claims.memberId()),
                Long.toString(expiresAt.toEpochMilli())
        );
        return signingInput + DELIMITER + sign(signingInput);
    }

    private QueueTokenClaims parse(final String token) {
        if (token == null || token.isBlank()) {
            throw new QueueTokenException("queue token invalid");
        }

        try {
            String[] parts = token.split("\\.", -1);
            if (parts.length != 9 || !VERSION.equals(parts[0])) {
                throw new QueueTokenException("queue token invalid");
            }

            String signingInput = String.join(
                    DELIMITER,
                    parts[0],
                    parts[1],
                    parts[2],
                    parts[3],
                    parts[4],
                    parts[5],
                    parts[6],
                    parts[7]
            );
            if (!MessageDigest.isEqual(
                    sign(signingInput).getBytes(StandardCharsets.US_ASCII),
                    parts[8].getBytes(StandardCharsets.US_ASCII)
            )) {
                throw new QueueTokenException("queue token invalid");
            }

            long expiresAtMillis = readPositiveLongPart(parts[7], "expiresAt");
            if (clock.instant().toEpochMilli() >= expiresAtMillis) {
                throw new QueueTokenException("queue token expired");
            }

            return new QueueTokenClaims(
                    readPositiveLongPart(parts[1], "performanceId"),
                    decodeQueueId(parts[2]),
                    readNonNegativeIntPart(parts[3], "shardId"),
                    readPositiveLongPart(parts[4], "localSeq"),
                    readNonNegativeLongPart(parts[5], "slotId"),
                    readPositiveLongPart(parts[6], "memberId")
            );
        } catch (QueueTokenException exception) {
            throw exception;
        } catch (IllegalArgumentException exception) {
            throw new QueueTokenException("queue token invalid", exception);
        }
    }

    private String encodeQueueId(final String queueId) {
        return BASE64_URL_ENCODER.encodeToString(queueId.getBytes(StandardCharsets.UTF_8));
    }

    private String decodeQueueId(final String encodedQueueId) {
        String queueId = new String(BASE64_URL_DECODER.decode(encodedQueueId), StandardCharsets.UTF_8);
        if (queueId.isBlank()) {
            throw new QueueTokenException("queue token invalid queueId");
        }
        return queueId;
    }

    private Long readPositiveLongPart(final String value, final String name) {
        long parsed = readLongPart(value, name);
        if (parsed <= 0) {
            throw new QueueTokenException("queue token invalid " + name);
        }
        return parsed;
    }

    private Long readNonNegativeLongPart(final String value, final String name) {
        long parsed = readLongPart(value, name);
        if (parsed < 0) {
            throw new QueueTokenException("queue token invalid " + name);
        }
        return parsed;
    }

    private int readNonNegativeIntPart(final String value, final String name) {
        long parsed = readNonNegativeLongPart(value, name);
        if (parsed > Integer.MAX_VALUE) {
            throw new QueueTokenException("queue token invalid " + name);
        }
        return Math.toIntExact(parsed);
    }

    private long readLongPart(final String value, final String name) {
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException exception) {
            throw new QueueTokenException("queue token invalid " + name, exception);
        }
    }

    private String sign(final String signingInput) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(secretKey, HMAC_ALGORITHM));
            byte[] signature = mac.doFinal(signingInput.getBytes(StandardCharsets.UTF_8));
            return BASE64_URL_ENCODER.encodeToString(signature);
        } catch (NoSuchAlgorithmException | InvalidKeyException exception) {
            throw new IllegalStateException("queue token signer unavailable", exception);
        }
    }

    private String validateSecret(final QueueProperties properties) {
        Objects.requireNonNull(properties, "properties must not be null");
        String secret = properties.getQueueTokenSecret();
        if (secret == null || secret.isBlank()) {
            throw new IllegalArgumentException("app.queue.queue-token-secret must not be blank");
        }
        if (secret.getBytes(StandardCharsets.UTF_8).length < 32) {
            throw new IllegalArgumentException("app.queue.queue-token-secret must be at least 32 bytes for HmacSHA256");
        }
        return secret;
    }

    private void validateClaims(final QueueTokenClaims claims) {
        if (claims == null) {
            throw new IllegalArgumentException("claims must not be null");
        }
        validatePositive(claims.performanceId(), "performanceId");
        validateNotBlank(claims.queueId(), "queueId");
        validateNonNegative(claims.shardId(), "shardId");
        validatePositive(claims.localSeq(), "localSeq");
        validateNonNegative(claims.slotId(), "slotId");
        validatePositive(claims.memberId(), "memberId");
    }

    private void validateTtl(final Duration ttl) {
        if (ttl == null || ttl.isZero() || ttl.isNegative()) {
            throw new IllegalArgumentException("ttl must be positive");
        }
    }

    private void validatePositive(final Long value, final String name) {
        if (value == null || value <= 0) {
            throw new IllegalArgumentException(name + " must be positive");
        }
    }

    private void validateNonNegative(final long value, final String name) {
        if (value < 0) {
            throw new IllegalArgumentException(name + " must be non-negative");
        }
    }

    private void validateNonNegative(final Long value, final String name) {
        if (value == null || value < 0) {
            throw new IllegalArgumentException(name + " must be non-negative");
        }
    }

    private void validateNotBlank(final String value, final String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }
}

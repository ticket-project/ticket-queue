package com.ticket.queue.infra;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ticket.queue.application.AuthenticatedMember;
import com.ticket.queue.config.AccessTokenProperties;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Date;
import javax.crypto.SecretKey;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

class JwtQueueAccessTokenVerifierTest {

    private static final String SECRET_KEY = "0123456789abcdef0123456789abcdef";
    private static final Instant NOW = Instant.parse("2026-05-25T00:00:00Z");

    @Test
    void verify는_유효한_bearer_access_token에서_회원정보를_반환한다() {
        JwtQueueAccessTokenVerifier verifier = newVerifier();
        String token = issueToken(10L, "USER", NOW.plusSeconds(60));

        AuthenticatedMember member = verifier.verify("Bearer " + token);

        assertThat(member.memberId()).isEqualTo(10L);
        assertThat(member.role()).isEqualTo("USER");
    }

    @Test
    void verify는_만료된_access_token이면_401을_반환한다() {
        JwtQueueAccessTokenVerifier verifier = newVerifier();
        String token = issueToken(10L, "USER", NOW.minusSeconds(1));

        assertThatThrownBy(() -> verifier.verify("Bearer " + token))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(exception -> ((ResponseStatusException) exception).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void verify는_bearer_형식이_아니면_401을_반환한다() {
        JwtQueueAccessTokenVerifier verifier = newVerifier();

        assertThatThrownBy(() -> verifier.verify("access-token"))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(exception -> ((ResponseStatusException) exception).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    private JwtQueueAccessTokenVerifier newVerifier() {
        AccessTokenProperties properties = new AccessTokenProperties();
        properties.setIssuer("ticket");
        properties.setSecretKey(SECRET_KEY);
        return new JwtQueueAccessTokenVerifier(properties, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private String issueToken(final Long memberId, final String role, final Instant expiresAt) {
        SecretKey secretKey = Keys.hmacShaKeyFor(SECRET_KEY.getBytes(StandardCharsets.UTF_8));
        return Jwts.builder()
                .issuer("ticket")
                .subject(String.valueOf(memberId))
                .claim("role", role)
                .issuedAt(Date.from(NOW))
                .expiration(Date.from(expiresAt))
                .signWith(secretKey)
                .compact();
    }
}

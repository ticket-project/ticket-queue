package com.ticket.queue.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(QueueJwtProperties.class)
public class QueueAccessTokenAuthConfig {

    @Bean
    public AccessTokenAuthenticationFilter accessTokenAuthenticationFilter(final AccessTokenVerifier accessTokenVerifier) {
        return new AccessTokenAuthenticationFilter(accessTokenVerifier);
    }

    @Bean
    public AccessTokenVerifier accessTokenVerifier(final QueueJwtProperties properties) {
        return new QueueJwtTokenVerifier(properties);
    }
}

package com.ticket.queue.config;

import com.ticket.support.security.admission.AdmissionTokenService;
import com.ticket.support.security.jwt.JwtTokenVerifier;
import java.time.Clock;
import java.time.ZoneId;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
@EnableConfigurationProperties({
        QueueAdmissionProperties.class,
        QueueAdmissionTokenProperties.class,
        QueueJwtProperties.class,
        QueueRedirectProperties.class
})
public class QueueSecurityConfig {

    @Bean
    public SecurityFilterChain securityFilterChain(final HttpSecurity http) throws Exception {
        return http
                .csrf(csrf -> csrf.disable())
                .authorizeHttpRequests(authorize -> authorize.anyRequest().permitAll())
                .httpBasic(Customizer.withDefaults())
                .build();
    }

    @Bean
    public Clock clock() {
        return Clock.system(ZoneId.of("Asia/Seoul"));
    }

    @Bean
    public JwtTokenVerifier jwtTokenVerifier(
            final QueueJwtProperties queueJwtProperties,
            final Clock clock
    ) {
        return new JwtTokenVerifier(queueJwtProperties.toJwtProperties(), clock);
    }

    @Bean
    public AdmissionTokenService admissionTokenService(
            final QueueAdmissionTokenProperties queueAdmissionTokenProperties,
            final Clock clock
    ) {
        return new AdmissionTokenService(queueAdmissionTokenProperties.toAdmissionTokenProperties(), clock);
    }
}
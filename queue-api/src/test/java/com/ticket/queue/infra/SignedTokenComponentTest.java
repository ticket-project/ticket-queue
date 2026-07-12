package com.ticket.queue.infra;

import static org.assertj.core.api.Assertions.assertThat;

import com.ticket.queue.application.AdmissionTokenIssuer;
import com.ticket.queue.application.QueueTokenService;
import com.ticket.queue.config.AdmissionTokenProperties;
import com.ticket.queue.config.QueueProperties;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

class SignedTokenComponentTest {

    private static final String SECRET = "0123456789abcdef0123456789abcdef";

    @Test
    void spring_creates_signed_queue_token_service_with_queue_properties() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.registerBean(QueueProperties.class, this::queueProperties);
            context.register(SignedQueueTokenService.class);

            context.refresh();

            assertThat(context.getBean(QueueTokenService.class)).isInstanceOf(SignedQueueTokenService.class);
        }
    }

    @Test
    void spring_creates_signed_admission_token_issuer_with_admission_properties() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.registerBean(AdmissionTokenProperties.class, this::admissionTokenProperties);
            context.register(SignedAdmissionTokenIssuer.class);

            context.refresh();

            assertThat(context.getBean(AdmissionTokenIssuer.class)).isInstanceOf(SignedAdmissionTokenIssuer.class);
        }
    }

    private QueueProperties queueProperties() {
        QueueProperties properties = new QueueProperties();
        properties.setQueueTokenSecret(SECRET);
        return properties;
    }

    private AdmissionTokenProperties admissionTokenProperties() {
        AdmissionTokenProperties properties = new AdmissionTokenProperties();
        properties.setIssuer("ticket-queue");
        properties.setAudience("ticket-api");
        properties.setSecretKey(SECRET);
        return properties;
    }
}

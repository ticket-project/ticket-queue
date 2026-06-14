package com.ticket.queue;

import com.ticket.support.passport.web.config.PassportInternalAuthConfiguration;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Import;
import org.springframework.scheduling.annotation.EnableScheduling;

@EnableScheduling
@Import(PassportInternalAuthConfiguration.class)
@SpringBootApplication
public class QueueApiApplication {

    public static void main(final String[] args) {
        SpringApplication.run(QueueApiApplication.class, args);
    }
}

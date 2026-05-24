package com.ticket.queue;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@EnableScheduling
@SpringBootApplication(scanBasePackages = {"com.ticket.queue", "com.ticket.support"})
public class QueueApiApplication {

    public static void main(final String[] args) {
        SpringApplication.run(QueueApiApplication.class, args);
    }
}
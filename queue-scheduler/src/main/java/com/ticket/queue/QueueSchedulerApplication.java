package com.ticket.queue;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@EnableScheduling
@SpringBootApplication
public class QueueSchedulerApplication {

    public static void main(final String[] args) {
        SpringApplication.run(QueueSchedulerApplication.class, args);
    }
}

package com.ticket.queue.deploy;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class ModuleArchitectureTest {

    @Test
    void api_and_scheduler_are_independent_boot_applications_with_only_redis_shared() {
        String settings = read(Path.of("settings.gradle"));
        String apiBuild = read(Path.of("queue-api/build.gradle"));
        String schedulerBuild = read(Path.of("queue-scheduler/build.gradle"));
        String redisBuild = read(Path.of("queue-redis/build.gradle"));

        assertThat(settings)
                .contains("include 'queue-redis'")
                .contains("include 'queue-api'")
                .contains("include 'queue-scheduler'");

        assertThat(apiBuild)
                .contains("id 'org.springframework.boot'")
                .contains("implementation project(':queue-redis')")
                .doesNotContain("project(':queue-scheduler')");
        assertThat(schedulerBuild)
                .contains("id 'org.springframework.boot'")
                .contains("implementation project(':queue-redis')")
                .doesNotContain("project(':queue-api')");
        assertThat(redisBuild)
                .contains("id 'java-library'")
                .doesNotContain("id 'org.springframework.boot'");
    }

    @Test
    void scheduling_is_enabled_only_in_the_scheduler_application() {
        String apiApplication = read(Path.of(
                "queue-api/src/main/java/com/ticket/queue/QueueApiApplication.java"));
        String schedulerApplication = read(Path.of(
                "queue-scheduler/src/main/java/com/ticket/queue/QueueSchedulerApplication.java"));

        assertThat(apiApplication)
                .contains("class QueueApiApplication")
                .doesNotContain("EnableScheduling");
        assertThat(schedulerApplication)
                .contains("class QueueSchedulerApplication")
                .contains("@EnableScheduling");

        assertThat(Path.of(
                "queue-api/src/main/java/com/ticket/queue/application/AdvancementScheduler.java"))
                .doesNotExist();
        assertThat(Path.of(
                "queue-scheduler/src/main/java/com/ticket/queue/api/AdmissionController.java"))
                .doesNotExist();
    }

    private String read(final Path path) {
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }
}

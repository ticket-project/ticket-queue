package com.ticket.queue.deploy;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

class QueueCdnCacheArchitectureTest {

    private static final List<Path> MODULE_SOURCE_ROOTS = List.of(
            Path.of("queue-common/src/main/java/com/ticket/queue"),
            Path.of("queue-api/src/main/java/com/ticket/queue"),
            Path.of("queue-scheduler/src/main/java/com/ticket/queue")
    );

    private static final List<Path> STATIC_STATE_FILES = List.of(
            Path.of("queue-common/src/main/java/com/ticket/queue/domain/PublicStatePublisher.java"),
            Path.of("queue-common/src/main/java/com/ticket/queue/infra/FilePublicStatePublisher.java"),
            Path.of("queue-common/src/test/java/com/ticket/queue/infra/FilePublicStatePublisherTest.java")
    );

    @Test
    void static_public_state_file_publisher_is_removed() {
        assertThat(STATIC_STATE_FILES)
                .noneMatch(Files::exists);
    }

    @Test
    void queue_application_code_does_not_reference_static_state_origin() {
        String code = MODULE_SOURCE_ROOTS.stream()
                .map(this::readAll)
                .collect(Collectors.joining("\n"));

        assertThat(code)
                .doesNotContain("PublicStatePublisher")
                .doesNotContain("FilePublicStatePublisher")
                .doesNotContain("publicStateOutputDirectory")
                .doesNotContain("publicStatePathTemplate")
                .doesNotContain("queue-state/performances");
    }

    @Test
    void project_guidance_references_cloudflare_state_api_cache_document() {
        String guidance = read(Path.of("README.md"))
                + read(Path.of("deploy/README.md"))
                + read(Path.of(".github/copilot-instructions.md"))
                + read(Path.of(".github/workflows/claude-code-review.yml"));

        assertThat(guidance)
                .contains("docs/cloudflare-state-api-cache.md")
                .doesNotContain("docs/cloudflare-static-state.md")
                .doesNotContain("Cloudflare static state");
    }

    private String readAll(final Path root) {
        try (Stream<Path> files = Files.walk(root)) {
            StringBuilder builder = new StringBuilder();
            files.filter(Files::isRegularFile)
                    .filter(path -> path.toString().endsWith(".java"))
                    .sorted()
                    .forEach(path -> builder.append(read(path)).append('\n'));
            return builder.toString();
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    private String read(final Path path) {
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }
}

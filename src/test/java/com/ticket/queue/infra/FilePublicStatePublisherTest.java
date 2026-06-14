package com.ticket.queue.infra;

import static org.assertj.core.api.Assertions.assertThat;

import com.ticket.queue.config.QueueProperties;
import com.ticket.queue.domain.PublicState;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FilePublicStatePublisherTest {

    @TempDir
    private Path tempDir;

    @Test
    void publish_writes_public_state_json_to_default_queue_state_path() throws Exception {
        QueueProperties queueProperties = new QueueProperties();
        queueProperties.setPublicStateOutputDirectory(tempDir);
        FilePublicStatePublisher publisher = new FilePublicStatePublisher(queueProperties);

        publisher.publish(new PublicState(1L, "OPEN", 152_000L, 1_000_000L, 5_000L, 1_790_000_000_000L));

        Path output = tempDir.resolve("queue-state/performances/1.json");
        assertThat(output).exists();
        assertThat(Files.readString(output))
                .isEqualTo("""
                        {"performanceId":1,"status":"OPEN","admittedUntilSeq":152000,"tailSeq":1000000,"refreshAfterMs":5000,"serverTimeMillis":1790000000000}
                        """.trim());
    }

    @Test
    void publish_writes_public_state_json_to_configured_static_origin_path() throws Exception {
        QueueProperties queueProperties = new QueueProperties();
        queueProperties.setPublicStateOutputDirectory(tempDir);
        queueProperties.setPublicStatePathTemplate("events/{performanceId}/queue-state.json");
        FilePublicStatePublisher publisher = new FilePublicStatePublisher(queueProperties);

        publisher.publish(new PublicState(1L, "OPEN", 152_000L, 1_000_000L, 5_000L, 1_790_000_000_000L));

        Path output = tempDir.resolve("events/1/queue-state.json");
        assertThat(output).exists();
        assertThat(Files.readString(output))
                .isEqualTo("""
                        {"performanceId":1,"status":"OPEN","admittedUntilSeq":152000,"tailSeq":1000000,"refreshAfterMs":5000,"serverTimeMillis":1790000000000}
                        """.trim());
    }
}

package com.ticket.queue.infra;

import com.ticket.queue.config.QueueProperties;
import com.ticket.queue.domain.PublicState;
import com.ticket.queue.domain.PublicStatePublisher;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class FilePublicStatePublisher implements PublicStatePublisher {

    private static final String PERFORMANCE_ID_PLACEHOLDER = "{performanceId}";
    private static final String TEMP_SUFFIX = ".tmp";

    private final QueueProperties queueProperties;

    @Override
    public void publish(final PublicState publicState) {
        validate(publicState);
        Path target = resolveTarget(publicState.performanceId());
        try {
            writeAtomically(target, toJson(publicState));
        } catch (IOException exception) {
            throw new IllegalStateException("failed to publish public queue state", exception);
        }
    }

    private Path resolveTarget(final Long performanceId) {
        Path root = queueProperties.getPublicStateOutputDirectory()
                .toAbsolutePath()
                .normalize();
        String relativePath = normalizedRelativePath(performanceId);

        Path target = root.resolve(relativePath).normalize();
        if (!target.startsWith(root)) {
            throw new IllegalArgumentException("public state path must stay within output directory");
        }
        return target;
    }

    private String normalizedRelativePath(final Long performanceId) {
        String relativePath = queueProperties.getPublicStatePathTemplate()
                .replace(PERFORMANCE_ID_PLACEHOLDER, String.valueOf(performanceId))
                .replace('\\', '/');
        while (relativePath.startsWith("/")) {
            relativePath = relativePath.substring(1);
        }
        return relativePath;
    }

    private void writeAtomically(
            final Path target,
            final String content
    ) throws IOException {
        Files.createDirectories(target.getParent());
        Path tempFile = Files.createTempFile(target.getParent(), target.getFileName().toString(), TEMP_SUFFIX);
        Files.writeString(tempFile, content, StandardCharsets.UTF_8);
        moveToTarget(tempFile, target);
    }

    private void moveToTarget(final Path tempFile, final Path target) throws IOException {
        try {
            Files.move(tempFile, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException exception) {
            Files.move(tempFile, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private String toJson(final PublicState publicState) {
        return new StringBuilder()
                .append("{")
                .append("\"performanceId\":").append(publicState.performanceId())
                .append(",\"status\":\"").append(escape(publicState.status())).append("\"")
                .append(",\"admittedUntilSeq\":").append(publicState.admittedUntilSeq())
                .append(",\"tailSeq\":").append(publicState.tailSeq())
                .append(",\"refreshAfterMs\":").append(publicState.refreshAfterMs())
                .append(",\"serverTimeMillis\":").append(publicState.serverTimeMillis())
                .append("}")
                .toString();
    }

    private String escape(final String value) {
        if (value == null) {
            return "";
        }
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private void validate(final PublicState publicState) {
        if (publicState == null) {
            throw new IllegalArgumentException("publicState must not be null");
        }
        if (publicState.performanceId() == null || publicState.performanceId() <= 0) {
            throw new IllegalArgumentException("performanceId must be positive");
        }
    }
}

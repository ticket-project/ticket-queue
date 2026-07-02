package com.ticket.queue.deploy;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class ClaudeWorkflowConfigTest {

    @Test
    void claude_comment_workflow_requires_explicit_claude_mention_without_cancelling_existing_runs() {
        String workflow = read(Path.of(".github/workflows/claude.yml"));

        assertThat(workflow)
                .contains("issue_comment:")
                .contains("startsWith(github.event.comment.body, '@claude')")
                .doesNotContain("cancel-in-progress: true");
    }

    @Test
    void claude_review_workflow_runs_when_pull_request_is_opened_or_ready_for_review() {
        String workflow = read(Path.of(".github/workflows/claude-code-review.yml"));

        assertThat(workflow)
                .contains("workflow_dispatch:")
                .contains("pull_request:")
                .contains("types: [opened, ready_for_review, reopened]")
                .contains("github.event.pull_request.number || inputs.pr_number");
    }

    private String read(final Path path) {
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }
}

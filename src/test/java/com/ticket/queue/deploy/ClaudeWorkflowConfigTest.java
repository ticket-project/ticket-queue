package com.ticket.queue.deploy;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

class ClaudeWorkflowConfigTest {

    @Test
    void claude_comment_workflow_requires_explicit_claude_mention_without_cancelling_existing_runs() {
        Map<Object, Object> workflow = readWorkflow(Path.of(".github/workflows/claude.yml"));
        Map<Object, Object> triggers = map(workflowTrigger(workflow));
        Map<Object, Object> issueComment = map(triggers.get("issue_comment"));
        Map<Object, Object> jobs = map(workflow.get("jobs"));
        Map<Object, Object> claude = map(jobs.get("claude"));

        assertThat(list(issueComment.get("types"))).containsExactly("created");
        assertThat(claude.get("if").toString()).contains("startsWith(github.event.comment.body, '@claude')");
        assertThat(workflow).doesNotContainKey("concurrency");
    }

    @Test
    void claude_review_workflow_runs_when_pull_request_is_opened_or_ready_for_review() {
        Map<Object, Object> workflow = readWorkflow(Path.of(".github/workflows/claude-code-review.yml"));
        Map<Object, Object> triggers = map(workflowTrigger(workflow));
        Map<Object, Object> pullRequest = map(triggers.get("pull_request"));
        Map<Object, Object> jobs = map(workflow.get("jobs"));
        Map<Object, Object> reviewJob = map(jobs.get("claude-review"));
        Map<Object, Object> reviewStep = findStep(reviewJob, "claude-review");
        Map<Object, Object> with = map(reviewStep.get("with"));

        assertThat(triggers).containsKey("workflow_dispatch");
        assertThat(list(pullRequest.get("types"))).containsExactly("opened", "ready_for_review", "reopened");
        assertThat(reviewJob.get("if").toString())
                .contains("github.event_name == 'workflow_dispatch'")
                .contains("github.event.pull_request.draft == false");
        assertThat(with.get("prompt").toString()).contains("github.event.pull_request.number || inputs.pr_number");
    }

    @SuppressWarnings("unchecked")
    private Map<Object, Object> readWorkflow(final Path path) {
        Object loaded = new Yaml().load(read(path));
        assertThat(loaded).isInstanceOf(Map.class);
        return (Map<Object, Object>) loaded;
    }

    private Object workflowTrigger(final Map<Object, Object> workflow) {
        Object trigger = workflow.get("on");
        return trigger == null ? workflow.get(Boolean.TRUE) : trigger;
    }

    @SuppressWarnings("unchecked")
    private Map<Object, Object> map(final Object value) {
        assertThat(value).isInstanceOf(Map.class);
        return (Map<Object, Object>) value;
    }

    @SuppressWarnings("unchecked")
    private List<Object> list(final Object value) {
        assertThat(value).isInstanceOf(List.class);
        return (List<Object>) value;
    }

    @SuppressWarnings("unchecked")
    private Map<Object, Object> findStep(final Map<Object, Object> job, final String id) {
        List<Object> steps = list(job.get("steps"));
        return steps.stream()
                .map(step -> (Map<Object, Object>) step)
                .filter(step -> id.equals(step.get("id")))
                .findFirst()
                .orElseThrow();
    }

    private String read(final Path path) {
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }
}

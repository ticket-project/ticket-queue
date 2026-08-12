package com.ticket.queue.application;

import com.ticket.queue.domain.EnterResult;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class QueueAdmissionMetrics {

    private static final String JOIN_METRIC = "queue.admission.join";
    private static final String ENTER_METRIC = "queue.admission.enter";
    private static final String PATH_SHARDED = "sharded";
    private static final String PATH_LEGACY = "legacy";
    private static final String PATH_UNKNOWN = "unknown";

    private final MeterRegistry meterRegistry;

    public QueueAdmissionMetrics(final MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
        List.of("created", "duplicate")
                .forEach(result -> joinCounter(result));
        List.of(PATH_SHARDED, PATH_LEGACY)
                .forEach(path -> List.of("admitted", "not_admitted", "expired")
                        .forEach(result -> enterCounter(path, result)));
        enterCounter(PATH_UNKNOWN, "invalid_token");
        enterCounter(PATH_SHARDED, "performance_mismatch");
        enterCounter(PATH_LEGACY, "performance_mismatch");
    }

    public void recordJoin(final boolean created) {
        joinCounter(created ? "created" : "duplicate").increment();
    }

    public void recordEnter(final boolean legacy, final EnterResult.Status status) {
        enterCounter(path(legacy), result(status)).increment();
    }

    public void recordInvalidToken() {
        enterCounter(PATH_UNKNOWN, "invalid_token").increment();
    }

    public void recordPerformanceMismatch(final boolean legacy) {
        enterCounter(path(legacy), "performance_mismatch").increment();
    }

    private Counter joinCounter(final String result) {
        return meterRegistry.counter(JOIN_METRIC, "result", result);
    }

    private Counter enterCounter(
            final String path,
            final String result
    ) {
        return meterRegistry.counter(ENTER_METRIC, "path", path, "result", result);
    }

    private String path(final boolean legacy) {
        return legacy ? PATH_LEGACY : PATH_SHARDED;
    }

    private String result(final EnterResult.Status status) {
        return switch (status) {
            case ADMITTED -> "admitted";
            case NOT_ADMITTED -> "not_admitted";
            case EXPIRED -> "expired";
        };
    }
}

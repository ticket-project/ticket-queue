package com.ticket.queue.application;

import com.ticket.queue.config.AdvancementProperties;
import com.ticket.queue.domain.QueueAdvanceResult;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.stereotype.Component;

@Component
public class QueueSchedulerMetrics {

    private static final String ADVANCE_METRIC = "queue.scheduler.advance";
    private static final String CYCLE_METRIC = "queue.scheduler.cycle.duration";

    private final MeterRegistry meterRegistry;
    private final AtomicLong waitingPerformances = new AtomicLong();
    private final AtomicLong backlog = new AtomicLong();
    private final AtomicLong oldestPendingSlotAgeMillis = new AtomicLong();

    public QueueSchedulerMetrics(
            final MeterRegistry meterRegistry,
            final AdvancementProperties advancementProperties
    ) {
        this.meterRegistry = meterRegistry;
        Gauge.builder("queue.scheduler.waiting.performances", waitingPerformances, AtomicLong::get)
                .register(meterRegistry);
        Gauge.builder("queue.scheduler.backlog", backlog, AtomicLong::get)
                .register(meterRegistry);
        Gauge.builder(
                        "queue.scheduler.oldest.pending.slot.age",
                        oldestPendingSlotAgeMillis,
                        value -> value.doubleValue() / 1_000.0
                )
                .baseUnit("seconds")
                .register(meterRegistry);
        Gauge.builder(
                        "queue.scheduler.advance.batch.configured",
                        advancementProperties,
                        AdvancementProperties::getAdvanceBatchSize
                )
                .register(meterRegistry);
        List.of("success", "skipped", "advance_failure")
                .forEach(this::advanceCounter);
        List.of("success", "partial_failure", "waiting_performance_discovery_failure")
                .forEach(this::cycleTimer);
        meterRegistry.counter("queue.scheduler.admitted");
    }

    public void recordQueueSnapshot(
            final int waitingPerformanceCount,
            final List<QueueAdvanceResult> results
    ) {
        waitingPerformances.set(waitingPerformanceCount);
        List<QueueAdvanceResult> observed = results.stream()
                .filter(QueueAdvanceResult::observed)
                .toList();
        if (waitingPerformanceCount == 0 || !observed.isEmpty()) {
            backlog.set(observed.stream().mapToLong(QueueAdvanceResult::backlog).sum());
            oldestPendingSlotAgeMillis.set(observed.stream()
                    .mapToLong(QueueAdvanceResult::oldestPendingSlotAgeMillis)
                    .max()
                    .orElse(0L));
        }
    }

    public void recordAdvance(final QueueAdvanceResult result) {
        if (!result.observed()) {
            advanceCounter("skipped").increment();
            return;
        }
        advanceCounter("success").increment();
        if (result.admittedCount() > 0) {
            meterRegistry.counter("queue.scheduler.admitted").increment(result.admittedCount());
        }
    }

    public void recordAdvanceFailure() {
        advanceCounter("advance_failure").increment();
    }

    public void recordCycle(final long durationNanos, final boolean partialFailure) {
        cycleTimer(partialFailure ? "partial_failure" : "success")
                .record(durationNanos, TimeUnit.NANOSECONDS);
    }

    public void recordDiscoveryFailure(final long durationNanos) {
        cycleTimer("waiting_performance_discovery_failure")
                .record(durationNanos, TimeUnit.NANOSECONDS);
    }

    private Counter advanceCounter(final String result) {
        return meterRegistry.counter(ADVANCE_METRIC, "result", result);
    }

    private Timer cycleTimer(final String result) {
        return meterRegistry.timer(CYCLE_METRIC, "result", result);
    }
}

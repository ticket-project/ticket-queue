package com.ticket.queue.application;

import com.ticket.queue.config.AdvancementProperties;
import com.ticket.queue.domain.QueueAdvancementStore;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class AdvancementScheduler {

    private final QueueAdvancementStore queueAdvancementStore;
    private final AdvancementProperties properties;
    private final AtomicInteger currentAdvanceBatchSize;
    private final AtomicInteger overrideApplied = new AtomicInteger();
    private final AtomicInteger waitingPerformances = new AtomicInteger();
    private final Counter overrideRejected;
    private final Counter overrideReadFailures;
    private final Counter admitted;
    private String lastRejectedOverride;

    public AdvancementScheduler(
            final QueueAdvancementStore queueAdvancementStore,
            final AdvancementProperties properties,
            final MeterRegistry meterRegistry
    ) {
        this.queueAdvancementStore = queueAdvancementStore;
        this.properties = properties;
        this.currentAdvanceBatchSize = new AtomicInteger(properties.getAdvanceBatchSize());
        Gauge.builder("queue.scheduler.advance.batch.size", currentAdvanceBatchSize, AtomicInteger::get)
                .description("회차 하나에 이번 실행에서 적용한 입장 한도")
                .register(meterRegistry);
        Gauge.builder("queue.scheduler.advance.batch.size.override", overrideApplied, AtomicInteger::get)
                .description("Redis override를 적용 중이면 1, 환경 변수 기본값이면 0")
                .register(meterRegistry);
        Gauge.builder("queue.scheduler.waiting.performances", waitingPerformances, AtomicInteger::get)
                .description("이번 실행에서 전진시킨 대기 중 회차 수")
                .register(meterRegistry);
        this.overrideRejected = Counter.builder("queue.scheduler.advance.batch.size.override.rejected")
                .description("정수가 아니거나 음수라 무시한 override를 읽은 실행 수")
                .register(meterRegistry);
        this.overrideReadFailures = Counter.builder("queue.scheduler.advance.batch.size.override.read.failures")
                .description("override 읽기에 실패한 실행 수")
                .register(meterRegistry);
        this.admitted = Counter.builder("queue.scheduler.admitted")
                .description("입장 허용으로 전진시킨 사용자 수")
                .register(meterRegistry);
    }

    @Scheduled(fixedDelayString = "${app.queue.advance-interval-ms:1000}")
    public void advanceWaitingQueues() {
        int advanceBatchSize = resolveAdvanceBatchSize();
        Set<Long> performanceIds = queueAdvancementStore.findWaitingPerformanceIds();
        waitingPerformances.set(performanceIds.size());
        performanceIds.forEach(performanceId -> advanceSafely(performanceId, advanceBatchSize));
    }

    private int resolveAdvanceBatchSize() {
        final Optional<String> override;
        try {
            override = queueAdvancementStore.findAdvanceBatchSizeOverride();
        } catch (RuntimeException exception) {
            // 읽기에 실패했다고 기본값으로 돌아가면, 운영자가 0으로 멈춰 둔 입장이 풀린다.
            overrideReadFailures.increment();
            log.warn("failed to read advance batch size override; keeping advanceBatchSize={}",
                    currentAdvanceBatchSize.get(), exception);
            return currentAdvanceBatchSize.get();
        }
        if (override.isEmpty()) {
            return apply(properties.getAdvanceBatchSize(), false);
        }

        String rawOverride = override.get();
        OptionalInt parsed = parseOverride(rawOverride);
        if (parsed.isEmpty()) {
            overrideRejected.increment();
            if (!Objects.equals(rawOverride, lastRejectedOverride)) {
                log.warn("ignored invalid advance batch size override value={}; keeping advanceBatchSize={}",
                        rawOverride, currentAdvanceBatchSize.get());
                lastRejectedOverride = rawOverride;
            }
            return currentAdvanceBatchSize.get();
        }
        return apply(parsed.getAsInt(), true);
    }

    private int apply(final int advanceBatchSize, final boolean fromOverride) {
        int previous = currentAdvanceBatchSize.getAndSet(advanceBatchSize);
        if (previous != advanceBatchSize) {
            log.info("advance batch size changed from {} to {} source={}",
                    previous, advanceBatchSize, fromOverride ? "redis" : "config");
        }
        overrideApplied.set(fromOverride ? 1 : 0);
        lastRejectedOverride = null;
        return advanceBatchSize;
    }

    private static OptionalInt parseOverride(final String value) {
        try {
            int parsed = Integer.parseInt(value.trim());
            return parsed >= 0 ? OptionalInt.of(parsed) : OptionalInt.empty();
        } catch (NumberFormatException exception) {
            return OptionalInt.empty();
        }
    }

    private void advanceSafely(final Long performanceId, final int advanceBatchSize) {
        try {
            int advanced = queueAdvancementStore.advancePublicState(
                    performanceId,
                    advanceBatchSize,
                    properties.getShardCount(),
                    properties.getSlotSizeMillis(),
                    properties.getSlotCloseGraceMillis(),
                    properties.getDefaultQueueTtl(),
                    properties.getDefaultRefreshAfterMs()
            );
            admitted.increment(advanced);
        } catch (RuntimeException exception) {
            log.warn("failed to advance queue performanceId={}", performanceId, exception);
        }
    }
}

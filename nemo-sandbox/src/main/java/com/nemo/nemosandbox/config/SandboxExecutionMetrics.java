package com.nemo.nemosandbox.config;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

@Component
public class SandboxExecutionMetrics {
    private final MeterRegistry registry;
    private final Counter cleanupFailures;
    private final Counter orphanReaped;

    public SandboxExecutionMetrics(MeterRegistry registry) {
        this.registry = registry;
        cleanupFailures = Counter.builder("nemo_sandbox_container_cleanup_failures_total").register(registry);
        orphanReaped = Counter.builder("nemo_sandbox_orphan_containers_reaped_total").register(registry);
    }

    public void record(String stage, long nanos) {
        Timer.builder("nemo_sandbox_stage_duration").tag("stage", stage)
                .publishPercentileHistogram().minimumExpectedValue(Duration.ofMillis(1))
                .maximumExpectedValue(Duration.ofMinutes(10)).register(registry)
                .record(nanos, TimeUnit.NANOSECONDS);
    }

    public void cleanupFailed() { cleanupFailures.increment(); }
    public void orphanReaped() { orphanReaped.increment(); }
    public void prefetch(String outcome) { registry.counter("nemo_sandbox_prefetch_total","outcome",outcome).increment(); }
    public void compileCache(String outcome) { registry.counter("nemo_sandbox_trusted_compile_cache_total","outcome",outcome).increment(); }
}

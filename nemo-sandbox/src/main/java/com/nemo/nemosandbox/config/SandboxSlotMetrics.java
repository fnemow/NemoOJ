package com.nemo.nemosandbox.config;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/**
 * 沙箱槽位指标（T10）。
 * <p>
 * 暴露"正在执行的任务数"与"可用槽位"，用来回答"沙箱是否已经打满"。
 * 只看并发上限配置无法判断实际使用率，也无法在打满时及时告警。
 * 无标签（单实例内部状态），基数恒为 1。
 */
@Component
public class SandboxSlotMetrics {

    public SandboxSlotMetrics(TaskConcurrencyLimiter limiter, MeterRegistry registry) {
        for (var priority : TaskConcurrencyLimiter.Priority.values()) {
            Gauge.builder("nemo_sandbox_tasks_waiting", limiter, l -> l.waitingTasks(priority))
                    .tag("priority", priority.name()).register(registry);
        }
        Gauge.builder("nemo_sandbox_tasks_running", limiter, l -> (double) l.runningTasks())
                .description("沙箱当前正在执行的任务数")
                .register(registry);
        Gauge.builder("nemo_sandbox_slots_available", limiter, l -> (double) l.availablePermits())
                .description("沙箱当前可用槽位")
                .register(registry);
    }
}

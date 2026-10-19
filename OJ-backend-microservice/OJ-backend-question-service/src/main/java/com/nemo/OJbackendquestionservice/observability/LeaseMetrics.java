package com.nemo.OJbackendquestionservice.observability;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 判题租约回收指标（T10）。
 * <p>
 * 区分两种结局：重新排队（容器/进程失联但任务还能救）与判为终态（执行次数超限）。
 * 前者偶发正常，持续升高说明判题进程在反复失联；后者意味着有提交被判题放弃。
 * 标签只用固定的两种取值，基数有界。
 */
@Component
public class LeaseMetrics {

    public static final String OUTCOME_REQUEUED = "requeued";
    public static final String OUTCOME_TERMINAL = "terminal";

    private final MeterRegistry registry;
    private final Map<String, Counter> counters = new ConcurrentHashMap<>();

    public LeaseMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    public void recordReclaim(String outcome) {
        counters.computeIfAbsent(outcome, o -> Counter.builder("nemo_judge_lease_reclaimed_total")
                .description("因租约过期被回收的判题任务数")
                .tag("outcome", o)
                .register(registry)).increment();
    }
}

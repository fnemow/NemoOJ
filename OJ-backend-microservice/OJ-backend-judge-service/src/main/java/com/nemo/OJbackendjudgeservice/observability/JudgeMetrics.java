package com.nemo.OJbackendjudgeservice.observability;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import com.nemo.OJbackendmodel.model.entity.QuestionSubmit;
import java.time.Duration;
import java.util.Date;
import java.util.concurrent.TimeUnit;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 判题结果分布指标（T10）。
 * <p>
 * 标签只用**有限的判题结果枚举**（AC/WA/CE/TLE/MLE/OLE/RE/SE）。
 * 绝不能把 submitId 放进标签：那会让时间序列数随提交量无界增长，
 * 监控系统会先于业务被压垮。单条提交的追踪靠日志中的 requestId。
 */
@Component
public class JudgeMetrics {

    public static final String ACCEPTED = "AC";
    public static final String WRONG_ANSWER = "WA";
    public static final String COMPILE_ERROR = "CE";
    public static final String TIME_LIMIT = "TLE";
    public static final String MEMORY_LIMIT = "MLE";
    public static final String OUTPUT_LIMIT = "OLE";
    public static final String RUNTIME_ERROR = "RE";
    public static final String SYSTEM_ERROR = "SE";

    private final MeterRegistry registry;

    /** 按结果缓存 Counter，避免每次判题都走一次注册表查找 */
    private final Map<String, Counter> counters = new ConcurrentHashMap<>();

    public JudgeMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    /** 记录一次判题结果。result 必须是上面定义的有限取值 */
    public void recordResult(String result) {
        counters.computeIfAbsent(result, r -> Counter.builder("nemo_judge_result_total")
                .description("判题结果分布")
                .tag("result", r)
                .register(registry)).increment();
    }

    public void recordStage(String stage, QuestionSubmit submit, long nanos) {
        Timer.builder("nemo_judge_stage_duration")
                .description("判题内部阶段耗时")
                .tag("stage", stage)
                .tag("language", bounded(submit == null ? null : submit.getLanguage(), "unknown"))
                .tag("mode", bounded(submit == null ? null : submit.getExecutionMode(), "unknown"))
                .publishPercentileHistogram()
                .minimumExpectedValue(Duration.ofMillis(1)).maximumExpectedValue(Duration.ofMinutes(10))
                .register(registry).record(nanos, TimeUnit.NANOSECONDS);
    }

    public void recordQueueWait(QuestionSubmit submit) {
        if (submit == null || submit.getCreateTime() == null) return;
        long millis = Math.max(0, System.currentTimeMillis() - submit.getCreateTime().getTime());
        timer("nemo_judge_queue_wait", "消息进入数据库到消费者开始处理", submit)
                .record(millis, TimeUnit.MILLISECONDS);
    }

    /** RabbitMQ publish to listener delivery, measured from a producer header. */
    public void recordBrokerQueueWait(Long publishedAtEpochMs) {
        if (publishedAtEpochMs == null || publishedAtEpochMs <= 0) return;
        long millis = Math.max(0, System.currentTimeMillis() - publishedAtEpochMs);
        Timer.builder("nemo_judge_broker_queue_wait")
                .description("RabbitMQ 发布到消费者开始处理的耗时")
                .publishPercentileHistogram().minimumExpectedValue(Duration.ofMillis(1))
                .maximumExpectedValue(Duration.ofMinutes(30)).register(registry)
                .record(millis, TimeUnit.MILLISECONDS);
    }

    public void recordEndToEnd(QuestionSubmit submit) {
        if (submit == null || submit.getCreateTime() == null) return;
        Date completed = submit.getUpdateTime() == null ? new Date() : submit.getUpdateTime();
        long millis = Math.max(0, completed.getTime() - submit.getCreateTime().getTime());
        timer("nemo_judge_end_to_end", "提交创建到终态耗时", submit)
                .record(millis, TimeUnit.MILLISECONDS);
    }

    private Timer timer(String name, String description, QuestionSubmit submit) {
        return Timer.builder(name).description(description)
                .tag("language", bounded(submit.getLanguage(), "unknown"))
                .tag("mode", bounded(submit.getExecutionMode(), "unknown"))
                .publishPercentileHistogram().minimumExpectedValue(Duration.ofMillis(1))
                .maximumExpectedValue(Duration.ofMinutes(30)).register(registry);
    }

    private String bounded(String value, String fallback) {
        if (value == null || value.isBlank()) return fallback;
        String normalized = value.toLowerCase(java.util.Locale.ROOT);
        return java.util.Set.of("java", "python", "cpp", "acm", "function").contains(normalized)
                ? normalized : fallback;
    }
}

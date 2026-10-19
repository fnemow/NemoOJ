package com.nemo.OJbackendjudgeservice.observability;

import com.nemo.OJbackendmodel.model.entity.QuestionSubmit;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.util.Date;

import static org.junit.jupiter.api.Assertions.assertEquals;

class JudgeMetricsTest {
    @Test void recordsOnlyBoundedTagsAndEndToEndTiming() {
        var registry = new SimpleMeterRegistry();
        var metrics = new JudgeMetrics(registry);
        var submit = new QuestionSubmit();
        submit.setLanguage("custom-unbounded-value");
        submit.setExecutionMode("FUNCTION");
        submit.setCreateTime(new Date(System.currentTimeMillis() - 100));
        submit.setUpdateTime(new Date());

        metrics.recordStage("sandbox", submit, 1_000_000);
        metrics.recordQueueWait(submit);
        metrics.recordEndToEnd(submit);

        assertEquals(1, registry.find("nemo_judge_stage_duration")
                .tag("stage", "sandbox").tag("language", "unknown").tag("mode", "function")
                .timer().count());
        assertEquals(1, registry.find("nemo_judge_end_to_end").timer().count());
    }
}

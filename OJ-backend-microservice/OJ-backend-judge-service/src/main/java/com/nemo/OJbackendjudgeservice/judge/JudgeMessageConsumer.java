package com.nemo.OJbackendjudgeservice.judge;

import com.nemo.OJbackendcommon.constant.JudgeMqConstant;
import com.nemo.OJbackendcommon.exception.BusinessException;
import com.nemo.OJbackendmodel.model.entity.QuestionSubmit;
import com.nemo.OJbackendjudgeservice.observability.JudgeQueueMetrics;
import com.nemo.OJbackendjudgeservice.observability.JudgeMetrics;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

import jakarta.annotation.Resource;

/**
 * 判题消息消费者。
 * <p>
 * 重试策略（消费者内部，不依赖 MQ 重投）：
 * - 业务异常（提交不存在等）→ 不重试，直接 ACK
 * - 系统异常（沙箱不可用）→ 内部重试 3 次（指数退避 1s/2s/4s），超限后
 *   将提交标记为 SYSTEM_ERROR 并 ACK
 * <p>
 * 使用内部重试而非 MQ 重投的原因：MQ 重投会产生第二条消息，第二条消息
 * 在 doJudge 中看到 UNDER_JUDGING 状态后会并发调用沙箱，破坏原子性。
 */
@Slf4j
@Component
public class JudgeMessageConsumer {

    private static final int MAX_RETRIES = 3;

    @Resource
    private JudgeService judgeService;

    @Resource
    private JudgeQueueMetrics queueMetrics;

    @Resource
    private JudgeMetrics judgeMetrics;

    @RabbitListener(queues = JudgeMqConstant.JUDGE_QUEUE)
    public void onJudgeMessage(long submitId,
                               @Header(name = "publishedAtEpochMs", required = false) Long publishedAtEpochMs) {
        queueMetrics.deliveryStarted();
        try {
            judgeMetrics.recordBrokerQueueWait(publishedAtEpochMs);
            consume(submitId);
        } finally {
            queueMetrics.deliveryFinished();
        }
    }

    /** Test/backward-compatible entry point for messages without the timing header. */
    public void onJudgeMessage(long submitId) {
        onJudgeMessage(submitId, null);
    }

    private void consume(long submitId) {
        log.info("收到判题消息, submitId={}", submitId);

        for (int attempt = 0; attempt < MAX_RETRIES; attempt++) {
            try {
                QuestionSubmit result = judgeService.doJudge(submitId);
                log.info("判题完成, submitId={}, status={}",
                        submitId, result != null ? result.getStatus() : "null");
                return; // 成功 → ACK
            } catch (BusinessException e) {
                // 业务异常 → 不重试
                log.error("判题业务异常, submitId={}, code={}", submitId, e.getCode());
                return;
            } catch (RuntimeException e) {
                if (InfrastructureRetry.isUnavailable(e)) {
                    log.warn("判题依赖暂不可用，5 秒后重投，submitId={}", submitId);
                    InfrastructureRetry.requeueAfterDelay(e);
                }
                // 系统异常 → 内部重试
                if (attempt < MAX_RETRIES - 1) {
                    long backoffMs = (long) Math.pow(2, attempt) * 1000L;
                    log.warn("判题系统异常, {}/{} 重试, {}ms 后重试, submitId={}",
                            attempt + 1, MAX_RETRIES, backoffMs, submitId, e);
                    try {
                        Thread.sleep(backoffMs);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        log.error("重试等待被中断, submitId={}", submitId);
                        throw new org.springframework.amqp.ImmediateRequeueAmqpException(
                                "Judge consumer interrupted; preserve message", ie);
                    }
                } else {
                    // 重试耗尽
                    log.error("判题系统异常，已耗尽 {} 次重试, submitId={}, 进入 DLQ",
                            MAX_RETRIES, submitId, e);
                    throw e; // 抛出 → RabbitMQ 路由到 DLQ
                }
            }
        }
    }
}

package com.nemo.OJbackendjudgeservice.judge;

import com.nemo.OJbackendcommon.constant.JudgeMqConstant;
import com.nemo.OJbackendmodel.model.entity.QuestionSubmit;
import com.nemo.OJbackendmodel.model.enums.QuestionSubmitStatusEnum;
import com.nemo.OJserviceclient.QuestionFeignClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import jakarta.annotation.Resource;

/**
 * 死信队列消费者。
 * <p>
 * 处理"耗尽消费重试次数"的判题消息。修改前的问题（证据 E06）：
 * 更新异常只记一条日志就被吞掉，随后消息被 ACK——任务从此无人处理，
 * 而外部只能看到它永远停在"待判题"。
 * <p>
 * 现在的三条约束：
 * <ol>
 *   <li><b>不覆盖已完成结果</b>：只有仍处于"待判题"的提交才会被置为系统错误。
 *       重复死信、过期死信走到这里是幂等返回。</li>
 *   <li><b>不动正在执行的提交</b>：状态为"判题中"说明可能仍有有效租约，
 *       交由租约回收器在过期后统一处理，避免误杀正在跑的任务。</li>
 *   <li><b>不吞失败</b>：更新失败时做有界重试（指数退避），仍失败则抛出异常，
 *       消息留在死信队列等待人工处理，而不是被静默确认后丢弃。</li>
 * </ol>
 */
@Slf4j
@Component
public class JudgeDlxConsumer {

    /** 写库失败时的重试次数；超过则抛出，让消息留在死信队列 */
    private static final int MAX_UPDATE_ATTEMPTS = 3;

    @Resource
    private QuestionFeignClient questionFeignClient;

    @RabbitListener(queues = JudgeMqConstant.JUDGE_DLX_QUEUE)
    public void onDlxMessage(long submitId) {
        log.error("判题消息进入死信队列, submitId={}", submitId);

        // 先判定当前状态，决定是否值得处理。读取失败不直接放弃：
        // 下面仍会尝试写入，写入本身也带状态条件，不会误伤。
        try {
            QuestionSubmit current = questionFeignClient.getQuestionSubmitById(submitId);
            if (current != null && current.getStatus() != null) {
                int status = current.getStatus();
                if (status == QuestionSubmitStatusEnum.SUCCEED.getValue()
                        || status == QuestionSubmitStatusEnum.FAILED.getValue()) {
                    log.info("提交 {} 已是终态({})，忽略本次死信，不覆盖结果", submitId, status);
                    return;
                }
                if (status == QuestionSubmitStatusEnum.UNDER_JUDGING.getValue()) {
                    log.warn("提交 {} 仍在判题中，忽略本次死信；租约过期后由回收器接管", submitId);
                    return;
                }
            }
        } catch (Exception e) {
            if (InfrastructureRetry.isUnavailable(e)) InfrastructureRetry.requeueAfterDelay(e);
            log.warn("读取死信提交状态失败，继续尝试写入（写入带状态条件）, submitId={}", submitId, e);
        }

        RuntimeException lastError = null;
        for (int attempt = 1; attempt <= MAX_UPDATE_ATTEMPTS; attempt++) {
            try {
                boolean updated = questionFeignClient.markFailedIfWaiting(
                        submitId, "判题消息进入死信队列，重试耗尽");
                if (updated) {
                    log.info("已将死信提交标记为 SYSTEM_ERROR, submitId={}", submitId);
                } else {
                    // 条件更新未命中：说明状态已不是"待判题"（已终态或被接管）。
                    // 这属于正常竞态，不是失败，直接结束而不重试。
                    log.info("死信提交状态已变更，无需处理, submitId={}", submitId);
                }
                return;
            } catch (RuntimeException e) {
                if (InfrastructureRetry.isUnavailable(e)) InfrastructureRetry.requeueAfterDelay(e);
                lastError = e;
                log.warn("标记死信提交失败, submitId={}, 第 {}/{} 次",
                        submitId, attempt, MAX_UPDATE_ATTEMPTS, e);
                sleepBackoff(attempt);
            }
        }

        // 不吞掉：抛出让消息留在死信队列，由人工按 deploy/migration/README.md 处理。
        // 此前这里 catch 住只记日志，等于把任务静默丢弃。
        throw new org.springframework.amqp.ImmediateRequeueAmqpException(
                "死信提交状态更新失败，已重试 " + MAX_UPDATE_ATTEMPTS + " 次，需人工处理: submitId=" + submitId,
                lastError);
    }

    private void sleepBackoff(int attempt) {
        try {
            Thread.sleep(500L * (1L << (attempt - 1)));
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }
}

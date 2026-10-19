package com.nemo.OJbackendquestionservice.service.impl;

import com.nemo.OJbackendmodel.model.entity.QuestionSubmit;
import com.nemo.OJbackendmodel.model.enums.JudgeInfoMessageEnum;
import com.nemo.OJbackendmodel.model.enums.QuestionSubmitStatusEnum;
import com.nemo.OJbackendquestionservice.mapper.QuestionSubmitMapper;
import com.nemo.OJbackendquestionservice.outbox.OutboxService;
import com.nemo.OJbackendcommon.ratelimit.RateLimitGuard;
import com.nemo.OJbackendquestionservice.observability.LeaseMetrics;
import com.nemo.OJbackendquestionservice.service.JudgeLeaseService;
import com.nemo.OJbackendquestionservice.service.StudyPlanProgressService;
import com.nemo.OJbackendquestionservice.progress.SubmissionProgressPublisher;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * 判题执行租约实现。
 * <p>
 * 所有写操作都下推到 Mapper 的条件更新里（单条 UPDATE 完成"校验+写入"），
 * 不做"先查后写"——那中间存在竞态窗口，正是旧执行者得以覆盖结果的原因。
 */
@Slf4j
@Service
public class JudgeLeaseServiceImpl implements JudgeLeaseService {

    private final QuestionSubmitMapper questionSubmitMapper;
    private final OutboxService outboxService;
    private final RateLimitGuard rateLimitGuard;
    private final LeaseMetrics leaseMetrics;
    private final StudyPlanProgressService studyPlanProgress;
    private final SubmissionProgressPublisher progressPublisher;

    /** 单个提交的最大执行次数，超过即判为系统错误终态 */
    @Value("${judge.lease.max-attempts:3}")
    private int maxAttempts;

    public JudgeLeaseServiceImpl(QuestionSubmitMapper questionSubmitMapper,
                                 OutboxService outboxService,
                                 RateLimitGuard rateLimitGuard,
                                 LeaseMetrics leaseMetrics,
                                 StudyPlanProgressService studyPlanProgress,
                                 SubmissionProgressPublisher progressPublisher) {
        this.questionSubmitMapper = questionSubmitMapper;
        this.outboxService = outboxService;
        this.rateLimitGuard = rateLimitGuard;
        this.leaseMetrics = leaseMetrics;
        this.studyPlanProgress = studyPlanProgress;
        this.progressPublisher = progressPublisher;
    }

    /**
     * 提交进入终态时释放该用户的"未完成提交"配额。
     * <p>
     * 不释放会导致配额只增不减：用户提交几次之后就被自己历史提交永久挡住。
     * 终态有多个出口（正常写回、死信兜底、超次数判失败），每一步都必须释放，
     * 因此统一在这里按提交 id 反查 userId 后释放。
     */
    private void releasePendingQuota(long submitId) {
        try {
            QuestionSubmit submit = questionSubmitMapper.selectById(submitId);
            if (submit != null && submit.getUserId() != null) {
                rateLimitGuard.releasePending(submit.getUserId());
            }
        } catch (Exception e) {
            // 释放失败只会让配额偏紧（TTL 到期后自然恢复），不影响判题正确性
            log.warn("释放未完成提交配额失败, submitId={}: {}", submitId, e.getMessage());
        }
    }

    @Override
    public String claim(long submitId, int leaseSeconds) {
        String token = UUID.randomUUID().toString().replace("-", "");
        int updated = questionSubmitMapper.claimForJudging(submitId, token, leaseSeconds);
        if (updated == 0) {
            return null;
        }
        log.info("抢占成功, submitId={}, token={}", submitId, token);
        progressPublisher.changed(submitId);
        return token;
    }

    @Override
    public boolean renewLease(long submitId, String token, int leaseSeconds) {
        return questionSubmitMapper.renewLease(submitId, token, leaseSeconds) > 0;
    }

    @Override
    public boolean finish(long submitId, String token, Integer status, String judgeInfoJson) {
        int updated = questionSubmitMapper.finishWithToken(submitId, token, status, judgeInfoJson);
        if (updated > 0) {
            if (status != null && status == QuestionSubmitStatusEnum.SUCCEED.getValue()) {
                try {
                    studyPlanProgress.completeForSubmission(submitId);
                } catch (Exception failure) {
                    // The verdict is authoritative; the periodic reconciler repairs progress.
                    log.warn("题单进度即时更新失败，等待补偿, submitId={}", submitId, failure);
                }
            }
            releasePendingQuota(submitId);
            progressPublisher.changed(submitId);
        }
        if (updated == 0) {
            // 不是错误：说明本次执行已被接管或结果已写入，属于正常的迟到写回
            log.warn("结果写回被拒绝（令牌不匹配或状态已终态）, submitId={}, token={}", submitId, token);
            return false;
        }
        return true;
    }

    @Override
    public boolean revertToWaiting(long submitId, String token, String reason) {
        boolean updated = questionSubmitMapper.revertToWaitingWithToken(submitId, token, truncate(reason, 500)) > 0;
        if (updated) progressPublisher.changed(submitId);
        return updated;
    }

    /**
     * 回收过期租约。
     * <p>
     * 先取出过期任务，再逐个条件更新：
     * <ul>
     *   <li>未超过最大执行次数 → 退回 WAITING 并**写入新的待投递事件**，
     *       使任务真正重新进入判题队列（只改状态而不重投，任务会静静地躺在库里）；</li>
     *   <li>超过最大执行次数 → 判为系统错误终态，不再重试。</li>
     * </ul>
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public int reclaimExpired(int batchSize) {
        List<QuestionSubmit> expired = questionSubmitMapper.listExpiredLeases(batchSize);
        int handled = 0;
        for (QuestionSubmit submit : expired) {
            long id = submit.getId();
            int attempts = submit.getAttemptCount() == null ? 0 : submit.getAttemptCount();

            if (attempts >= maxAttempts) {
                int updated = questionSubmitMapper.markSystemErrorTerminal(
                        id, submit.getExecutionToken(),
                        "执行次数超过上限(" + maxAttempts + ")，判定为系统错误");
                if (updated > 0) {
                    releasePendingQuota(id);
                    progressPublisher.changed(id);
                    leaseMetrics.recordReclaim(LeaseMetrics.OUTCOME_TERMINAL);
                    log.error("提交 {} 执行次数达上限 {}，判为系统错误终态", id, attempts);
                    handled++;
                }
                continue;
            }

            int updated = questionSubmitMapper.reclaimToWaiting(id, submit.getExecutionToken(),
                    "租约过期，已由回收器重新排队");
            if (updated > 0) {
                // 关键：必须重投消息，否则状态回到待判题却没有任何消费者会来处理
                outboxService.enqueue(OutboxService.EVENT_TYPE_JUDGE_SUBMIT, id, String.valueOf(id));
                progressPublisher.changed(id);
                leaseMetrics.recordReclaim(LeaseMetrics.OUTCOME_REQUEUED);
                log.warn("回收过期租约并重新排队: submitId={}, 已执行 {} 次", id, attempts);
                handled++;
            }
        }
        return handled;
    }

    @Override
    public boolean markFailedIfWaiting(long submitId, String reason) {
        String info = "{\"message\":\"" + JudgeInfoMessageEnum.SYSTEM_ERROR.getValue() + "\"}";
        boolean updated = questionSubmitMapper.markFailedIfWaiting(submitId, truncate(reason, 500), info) > 0;
        if (updated) {
            releasePendingQuota(submitId);
            progressPublisher.changed(submitId);
        }
        return updated;
    }

    @Override
    public QuestionSubmit getById(long submitId) {
        return questionSubmitMapper.selectById(submitId);
    }

    private String truncate(String s, int max) {
        if (s == null) {
            return null;
        }
        return s.length() <= max ? s : s.substring(0, max);
    }
}

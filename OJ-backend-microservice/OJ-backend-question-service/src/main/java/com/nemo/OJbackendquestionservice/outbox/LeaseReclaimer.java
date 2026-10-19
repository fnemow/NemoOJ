package com.nemo.OJbackendquestionservice.outbox;

import com.nemo.OJbackendquestionservice.service.JudgeLeaseService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 过期执行租约回收器。
 * <p>
 * **必须是独立组件**，不能放在判题消费者里：消费者进程被杀时，
 * 它自己已经不存在了，指望它回收自己的任务是不成立的。
 * 本回收器运行在题目服务（提交状态的归属方），因此与判题服务的存活解耦。
 * <p>
 * 恢复时间上界 = 租约时长 + 回收周期 + 重新投递耗时。
 * 默认租约 120s、回收周期 30s，因此**最坏约 150 秒**内任务会被重新排队。
 * 两个参数都由配置决定，调小可加快恢复，但会增加"长任务被误回收"的风险。
 */
@Slf4j
@Component
public class LeaseReclaimer {

    private final JudgeLeaseService judgeLeaseService;

    @Value("${judge.lease.reclaim-batch-size:50}")
    private int batchSize;

    public LeaseReclaimer(JudgeLeaseService judgeLeaseService) {
        this.judgeLeaseService = judgeLeaseService;
    }

    @Scheduled(fixedDelayString = "${judge.lease.reclaim-interval-ms:30000}")
    public void reclaim() {
        try {
            int handled = judgeLeaseService.reclaimExpired(batchSize);
            if (handled > 0) {
                log.warn("租约回收器处理了 {} 条失联任务（已重新排队或判定系统错误）", handled);
            }
        } catch (Exception e) {
            // 单轮失败不影响下一轮；异常吞掉前必须留痕，否则回收器静默失效很难发现
            log.error("租约回收失败，将在下一轮重试", e);
        }
    }
}

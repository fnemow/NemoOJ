package com.nemo.OJbackendjudgeservice.judge;

import com.nemo.OJserviceclient.QuestionFeignClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 判题执行续租心跳。
 * <p>
 * 为什么需要：判题可能持续很久（编译 + 多个用例，readTimeout 配置达 10 分钟）。
 * 若只在开始时获取一次租约，长任务必然被回收器误判为"失联"并重投，
 * 造成同一提交被并发执行。
 * <p>
 * 按 leaseSeconds / 3 的间隔续租：单轮续租失败（网络抖动、题目服务短暂不可用）
 * 不会立刻中断执行，租约本身仍在有效期内；只有确实被回收才会标记失联。
 */
@Slf4j
@Component
public class LeaseHeartbeat {

    private final QuestionFeignClient questionFeignClient;

    @Value("${judge.lease.seconds:120}")
    private int leaseSeconds;

    private final ScheduledExecutorService scheduler =
            Executors.newScheduledThreadPool(2, r -> {
                Thread t = new Thread(r, "judge-lease-heartbeat");
                t.setDaemon(true);
                return t;
            });

    public LeaseHeartbeat(QuestionFeignClient questionFeignClient) {
        this.questionFeignClient = questionFeignClient;
    }

    /**
     * 一次判题执行的续租会话。用 try-with-resources 或 finally 关闭。
     */
    public static final class Session implements AutoCloseable {
        private final AtomicBoolean leaseLost;
        private final ScheduledFuture<?> future;

        Session(AtomicBoolean leaseLost, ScheduledFuture<?> future) {
            this.leaseLost = leaseLost;
            this.future = future;
        }

        /** 租约是否已确认失效；为 true 时不应再写回结果 */
        public boolean isLeaseLost() {
            return leaseLost.get();
        }

        @Override
        public void close() {
            future.cancel(false);
        }
    }

    /**
     * 开始为某次执行续租。判题结束后必须关闭，否则线程会一直续租。
     */
    public Session start(long submitId, String token) {
        long intervalMs = Math.max(1000L, (leaseSeconds * 1000L) / 3);
        AtomicBoolean lost = new AtomicBoolean(false);

        ScheduledFuture<?> future = scheduler.scheduleAtFixedRate(() -> {
            try {
                boolean ok = questionFeignClient.renewLease(submitId, token, leaseSeconds);
                if (!ok && lost.compareAndSet(false, true)) {
                    log.warn("续租失败，租约已失效，本次执行的结果将被拒绝写回: submitId={}", submitId);
                }
            } catch (Exception e) {
                // 调用异常不判定失联：可能只是题目服务短暂不可用。
                // 真正失联时租约会自然到期，由回收器接管，不会漏。
                log.warn("续租调用异常（将重试）: submitId={}, {}", submitId, e.getMessage());
            }
        }, intervalMs, intervalMs, TimeUnit.MILLISECONDS);

        return new Session(lost, future);
    }
}

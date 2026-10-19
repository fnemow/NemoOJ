package com.nemo.OJbackendcommon.ratelimit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 限流器单元测试。
 * <p>
 * 本类只覆盖**本地实现**（Redis 实现需要真实 Redis，见集成测试）。
 * 这里重点是算法正确性与边界：窗口计数、拒绝时给出重试时间、
 * 并发下不超发、以及配额可正确释放。
 */
class RateLimiterTest {

    private InMemoryRateLimiter limiter;

    @BeforeEach
    void setUp() {
        limiter = new InMemoryRateLimiter();
    }

    // ---------- 滑动窗口 ----------

    @Test
    @DisplayName("窗口内达到上限后拒绝，并给出可识别的重试时间")
    void rejectsAfterLimitAndReportsRetryAfter() {
        for (int i = 1; i <= 3; i++) {
            assertTrue(limiter.tryAcquire("k", 3, 60).isAllowed(), "第 " + i + " 次应放行");
        }

        RateLimitDecision decision = limiter.tryAcquire("k", 3, 60);
        assertFalse(decision.isAllowed(), "超过上限应拒绝");
        assertTrue(decision.getRetryAfterSeconds() > 0, "应给出建议重试秒数，客户端才能退避");
        assertTrue(decision.getRetryAfterSeconds() <= 60, "重试时间不应超过窗口长度");
        assertEquals(4, decision.getCurrentCount());
        assertTrue(decision.toString().contains("REJECT"), "结果应可被日志识别");
    }

    @Test
    @DisplayName("窗口过期后计数重置")
    void windowResetsAfterExpiry() throws Exception {
        assertTrue(limiter.tryAcquire("k2", 1, 1).isAllowed());
        assertFalse(limiter.tryAcquire("k2", 1, 1).isAllowed(), "窗口内第二次应拒绝");

        Thread.sleep(1100);
        assertTrue(limiter.tryAcquire("k2", 1, 1).isAllowed(), "窗口过期后应重新放行");
    }

    @Test
    @DisplayName("滑动窗口只淘汰真正过期的请求，不会在固定边界整体清零")
    void keepsRecentAttemptsAcrossFormerFixedBoundary() throws Exception {
        assertTrue(limiter.tryAcquire("sliding", 2, 1).isAllowed());
        Thread.sleep(600);
        assertTrue(limiter.tryAcquire("sliding", 2, 1).isAllowed());
        Thread.sleep(500);
        assertTrue(limiter.tryAcquire("sliding", 2, 1).isAllowed(), "第一条已过期，应释放一个名额");
        assertFalse(limiter.tryAcquire("sliding", 2, 1).isAllowed(), "第二条仍在最近一秒内，不能整体清零");
    }

    @Test
    @DisplayName("不同维度键互不影响")
    void keysAreIndependent() {
        assertTrue(limiter.tryAcquire("login:ip:1.1.1.1", 1, 60).isAllowed());
        assertFalse(limiter.tryAcquire("login:ip:1.1.1.1", 1, 60).isAllowed());
        assertTrue(limiter.tryAcquire("login:ip:2.2.2.2", 1, 60).isAllowed(),
                "另一个 IP 不应受影响——这正是「只限 IP 不串号」的要求");
        assertTrue(limiter.tryAcquire("login:account:alice", 1, 60).isAllowed(),
                "账号维度独立于 IP 维度");
    }

    // ---------- 并发 ----------

    @Test
    @DisplayName("并发请求下放行数量不超过上限")
    void concurrentAcquiresDoNotExceedLimit() throws Exception {
        int limit = 10;
        int threads = 50;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch gate = new CountDownLatch(1);
        AtomicInteger allowed = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();

        try {
            for (int i = 0; i < threads; i++) {
                pool.submit(() -> {
                    try {
                        gate.await();
                        if (limiter.tryAcquire("concurrent", limit, 60).isAllowed()) {
                            allowed.incrementAndGet();
                        } else {
                            rejected.incrementAndGet();
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                });
            }
            gate.countDown();
            pool.shutdown();
            assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS));
        } finally {
            pool.shutdownNow();
        }

        assertEquals(limit, allowed.get(), "并发下放行数应恰好等于上限");
        assertEquals(threads - limit, rejected.get());
    }

    // ---------- 配额调整 ----------

    @Test
    @DisplayName("计数可查询、可增减；减到 0 后键被清除")
    void supportsCounterAdjustment() {
        limiter.tryAcquire("quota", 5, 3600);
        limiter.tryAcquire("quota", 5, 3600);
        assertEquals(2, limiter.currentCount("quota"));

        limiter.adjust("quota", -1);
        assertEquals(1, limiter.currentCount("quota"));

        limiter.adjust("quota", -5);
        assertEquals(0, limiter.currentCount("quota"), "减到 0 或以下应清除键，避免残留占用配额");
    }

    @Test
    @DisplayName("配额：被拒绝的占用不计数，释放后可再次占用")
    void quotaOccupyDoesNotCountRejections() {
        String key = "submit:pending:user:1";

        assertTrue(limiter.tryOccupy(key, 2, 3600));
        assertTrue(limiter.tryOccupy(key, 2, 3600));
        assertEquals(2, limiter.currentCount(key));

        // 关键：被拒绝的占用不能把计数推高。
        // 若这里用 tryAcquire，计数会涨到 3、4、5……，用户释放额度后仍被挡住。
        for (int i = 0; i < 5; i++) {
            assertFalse(limiter.tryOccupy(key, 2, 3600), "已达配额应拒绝");
        }
        assertEquals(2, limiter.currentCount(key), "被拒绝的占用不应改变计数");

        // 释放一个后应能再次占用
        limiter.adjust(key, -1);
        assertEquals(1, limiter.currentCount(key));
        assertTrue(limiter.tryOccupy(key, 2, 3600), "释放后应能再次占用");
        assertEquals(2, limiter.currentCount(key));
    }

    @Test
    @DisplayName("限流：被拒绝的尝试仍计数（防止无限重试）")
    void rateLimitCountsRejectedAttempts() {
        for (int i = 0; i < 3; i++) {
            limiter.tryAcquire("rl", 3, 60);
        }
        assertEquals(3, limiter.currentCount("rl"));
        // 继续尝试（被拒）——计数继续增长，这是限流的期望行为
        limiter.tryAcquire("rl", 3, 60);
        limiter.tryAcquire("rl", 3, 60);
        assertEquals(5, limiter.currentCount("rl"),
                "被拒绝的限流尝试应继续计数，否则攻击者可无限重试");
    }

    @Test
    @DisplayName("本地实现始终标记为降级状态")
    void reportsDegraded() {
        assertTrue(limiter.isDegraded(), "本地实现应被识别为降级，便于监控告警");
    }
}

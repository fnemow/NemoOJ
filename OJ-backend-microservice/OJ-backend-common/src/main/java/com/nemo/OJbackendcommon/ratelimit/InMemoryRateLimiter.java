package com.nemo.OJbackendcommon.ratelimit;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 进程内滑动窗口限流器。
 * <p>
 * **只用于降级**：Redis 不可用或单实例开发环境。
 * 多实例部署下它的配额会按实例数放大，不能作为生产的唯一防线——
 * 因此 {@link RedisRateLimiter} 在降级时会主动收紧限额。
 * <p>
 * 实现上做了两点必要防护：逐条淘汰滑动窗口外的请求记录；定期清理过期键，
 * 否则键会随访问维度（IP、账号）无界增长。
 */
public class InMemoryRateLimiter implements RateLimiter {

    /** 触发清理的键数量阈值 */
    private static final int CLEANUP_THRESHOLD = 10_000;

    private static final class Window {
        final AtomicLong counter = new AtomicLong(0);
        volatile long windowStartMs;

        Window(long windowStartMs) {
            this.windowStartMs = windowStartMs;
        }
    }

    private static final class SlidingWindow {
        final Deque<Long> attempts = new ArrayDeque<>();
        volatile long windowMs;

        SlidingWindow(long windowMs) {
            this.windowMs = windowMs;
        }
    }

    private final Map<String, SlidingWindow> slidingWindows = new ConcurrentHashMap<>();
    private final Map<String, Window> quotas = new ConcurrentHashMap<>();

    private final AtomicLong lastCleanupMs = new AtomicLong(System.currentTimeMillis());

    @Override
    public RateLimitDecision tryAcquire(String key, int limit, int windowSeconds) {
        long now = System.currentTimeMillis();
        maybeCleanup(now);

        long windowMs = windowSeconds * 1000L;
        SlidingWindow window = slidingWindows.computeIfAbsent(key, ignored -> new SlidingWindow(windowMs));
        synchronized (window) {
            window.windowMs = windowMs;
            prune(window, now);
            window.attempts.addLast(now);
            long count = window.attempts.size();
            if (count > limit) {
                int expiryIndex = (int) Math.min(count - 1, count - limit);
                long expiresAt = window.attempts.stream().skip(expiryIndex).findFirst().orElse(now) + windowMs;
                long retryAfter = Math.max(1, (long) Math.ceil((expiresAt - now) / 1000.0));
                return RateLimitDecision.reject(retryAfter, key, count);
            }
        }
        return RateLimitDecision.allow();
    }

    @Override
    public boolean tryOccupy(String key, int quota, int ttlSeconds) {
        long now = System.currentTimeMillis();
        Window window = quotas.compute(key, (k, existing) -> {
            if (existing == null || now - existing.windowStartMs >= ttlSeconds * 1000L) {
                return new Window(now);
            }
            return existing;
        });
        // 只有真正占用成功才累加；已达配额时保持计数不变
        while (true) {
            long current = window.counter.get();
            if (current >= quota) {
                return false;
            }
            if (window.counter.compareAndSet(current, current + 1)) {
                return true;
            }
        }
    }

    @Override
    public long currentCount(String key) {
        Window quota = quotas.get(key);
        if (quota != null) {
            return quota.counter.get();
        }
        SlidingWindow window = slidingWindows.get(key);
        if (window == null) return 0;
        synchronized (window) {
            prune(window, System.currentTimeMillis());
            return window.attempts.size();
        }
    }

    @Override
    public void adjust(String key, long delta) {
        Window quota = quotas.get(key);
        if (quota != null) {
            long after = quota.counter.addAndGet(delta);
            if (after <= 0) quotas.remove(key, quota);
            return;
        }
        SlidingWindow window = slidingWindows.get(key);
        if (window != null && delta != 0) {
            synchronized (window) {
                if (delta < 0) {
                    for (long i = 0; i < -delta && !window.attempts.isEmpty(); i++) window.attempts.removeFirst();
                } else {
                    long now = System.currentTimeMillis();
                    for (long i = 0; i < delta; i++) window.attempts.addLast(now);
                }
                if (window.attempts.isEmpty()) slidingWindows.remove(key, window);
            }
        }
    }

    @Override
    public boolean isDegraded() {
        return true;
    }

    /** 清理已过期的窗口，避免键无界增长（按时间间隔或键数量触发） */
    private void maybeCleanup(long now) {
        if (slidingWindows.size() + quotas.size() < CLEANUP_THRESHOLD) {
            return;
        }
        long last = lastCleanupMs.get();
        if (now - last < 60_000L || !lastCleanupMs.compareAndSet(last, now)) {
            return;
        }
        int before = slidingWindows.size() + quotas.size();
        slidingWindows.entrySet().removeIf(e -> {
            SlidingWindow window = e.getValue();
            synchronized (window) {
                prune(window, now);
                return window.attempts.isEmpty();
            }
        });
        quotas.entrySet().removeIf(e -> now - e.getValue().windowStartMs > 3600_000L);
        int removed = before - slidingWindows.size() - quotas.size();
        if (removed > 0) {
            org.slf4j.LoggerFactory.getLogger(InMemoryRateLimiter.class)
                    .debug("清理过期限流窗口 {} 个，当前 {} 个", removed,
                            slidingWindows.size() + quotas.size());
        }
    }

    private void prune(SlidingWindow window, long now) {
        long cutoff = now - window.windowMs;
        while (!window.attempts.isEmpty() && window.attempts.peekFirst() <= cutoff) {
            window.attempts.removeFirst();
        }
    }

    /** 仅供测试：清空全部窗口 */
    public void clear() {
        slidingWindows.clear();
        quotas.clear();
    }
}

package com.nemo.OJbackendcommon.ratelimit;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 基于 Redis 有序集合的滑动窗口限流器。
 * <p>
 * 计数写在 Redis 中，因此**多个实例共享同一份配额**，扩容不会放大限额。
 * <p>
 * 过期记录清理、本次请求写入、窗口计数和过期时间设置在同一段 Lua 中原子完成，
 * 保证并发实例看到相同的滑动窗口结果，并避免留下不会过期的限流键。
 * <p>
 * 依赖不可用时的行为见 {@link #degradedFallback}——按"有界降级"处理，
 * 而不是默认放行。
 */
@Slf4j
public class RedisRateLimiter implements RateLimiter {

    /**
     * KEYS[1]=滑动窗口有序集合键；ARGV[1]=窗口秒数；ARGV[2]=上限。
     * 返回当前计数（含本次）和建议重试秒数。
     */
    @SuppressWarnings("rawtypes")
    private static final RedisScript<List> SLIDING_WINDOW = new DefaultRedisScript<>(
            "local t=redis.call('TIME') "
                    + "local now=t[1]*1000+math.floor(t[2]/1000) "
                    + "local window=tonumber(ARGV[1])*1000 "
                    + "redis.call('ZREMRANGEBYSCORE',KEYS[1],'-inf',now-window) "
                    + "local seq=redis.call('INCR',KEYS[1]..':seq') "
                    + "redis.call('ZADD',KEYS[1],now,tostring(now)..':'..tostring(seq)) "
                    + "redis.call('PEXPIRE',KEYS[1],window) "
                    + "redis.call('PEXPIRE',KEYS[1]..':seq',window) "
                    + "local count=redis.call('ZCARD',KEYS[1]) "
                    + "local retry=0 "
                    + "local limit=tonumber(ARGV[2]) "
                    + "if count>limit then "
                    + " local target=redis.call('ZRANGE',KEYS[1],count-limit,count-limit,'WITHSCORES') "
                    + " if target[2] then retry=math.max(1,math.ceil((tonumber(target[2])+window-now)/1000)) else retry=tonumber(ARGV[1]) end "
                    + "end "
                    + "return {count,retry}",
            List.class);

    /**
     * KEYS[1]=配额键；ARGV[1]=配额上限；ARGV[2]=TTL 秒。
     * 未达上限则 +1 并返回新值；已达上限返回 -1 且不改变计数。
     * 判定与累加必须原子完成——分开做会出现"并发下双双通过"或"拒绝也涨计数"。
     */
    private static final RedisScript<Long> OCCUPY = new DefaultRedisScript<>(
            "local c = tonumber(redis.call('GET', KEYS[1]) or '0') "
                    + "if c >= tonumber(ARGV[1]) then return -1 end "
                    + "local n = redis.call('INCR', KEYS[1]) "
                    + "if n == 1 then redis.call('EXPIRE', KEYS[1], ARGV[2]) end "
                    + "return n",
            Long.class);

    private final StringRedisTemplate redis;

    /** 降级实现：Redis 不可用时使用的**保守**本地限额 */
    private final RateLimiter degradedFallback;

    private final AtomicBoolean degraded = new AtomicBoolean(false);

    /**
     * @param redisProvider        可能为 null（未配置 Redis 的服务）
     * @param degradedFallback     降级实现，通常为保守的本地限流
     */
    public RedisRateLimiter(ObjectProvider<StringRedisTemplate> redisProvider,
                            RateLimiter degradedFallback) {
        this.redis = redisProvider.getIfAvailable();
        this.degradedFallback = degradedFallback;
        if (this.redis == null) {
            log.warn("未配置 Redis，限流将使用本地降级实现；多实例下配额会按实例数放大，"
                    + "仅适用于单实例或开发环境");
        }
    }

    @Override
    public RateLimitDecision tryAcquire(String key, int limit, int windowSeconds) {
        if (redis == null) {
            return degradedFallback.tryAcquire(key, limited(limit, windowSeconds), windowSeconds);
        }
        try {
            @SuppressWarnings("unchecked")
            List<Long> result = redis.execute(SLIDING_WINDOW, Collections.singletonList(key),
                    String.valueOf(windowSeconds), String.valueOf(limit));
            if (result == null || result.size() < 2) {
                return degrade(key, limit, windowSeconds);
            }
            markHealthy();
            long count = result.get(0);
            if (count > limit) {
                long retryAfter = Math.max(1, result.get(1));
                return RateLimitDecision.reject(retryAfter, key, count);
            }
            return RateLimitDecision.allow();
        } catch (Exception e) {
            log.warn("限流依赖异常，切换到降级策略: {}", e.getMessage());
            return degrade(key, limit, windowSeconds);
        }
    }

    @Override
    public boolean tryOccupy(String key, int quota, int ttlSeconds) {
        if (redis == null) {
            return degradedFallback.tryOccupy(key, quota, ttlSeconds);
        }
        try {
            Long result = redis.execute(OCCUPY, Collections.singletonList(key),
                    String.valueOf(quota), String.valueOf(ttlSeconds));
            markHealthy();
            // -1 表示已达配额（未计数）；null 视为依赖异常，转降级
            if (result == null) {
                return degradedFallback.tryOccupy(key, quota, ttlSeconds);
            }
            return result >= 0;
        } catch (Exception e) {
            log.warn("占用配额失败，转降级实现: {}", e.getMessage());
            return degradedFallback.tryOccupy(key, quota, ttlSeconds);
        }
    }

    @Override
    public long currentCount(String key) {
        if (redis == null) {
            return degradedFallback.currentCount(key);
        }
        try {
            String value = redis.opsForValue().get(key);
            return value == null ? 0 : Long.parseLong(value);
        } catch (Exception e) {
            log.warn("读取限流计数失败，按降级实现处理: {}", e.getMessage());
            return degradedFallback.currentCount(key);
        }
    }

    @Override
    public void adjust(String key, long delta) {
        if (redis == null) {
            degradedFallback.adjust(key, delta);
            return;
        }
        try {
            if (delta >= 0) {
                redis.opsForValue().increment(key, delta);
            } else {
                Long after = redis.opsForValue().decrement(key, -delta);
                if (after != null && after <= 0) {
                    // 减到 0 或以下就删键，避免留下一个不会被清理的空计数
                    redis.delete(key);
                }
            }
        } catch (Exception e) {
            log.warn("调整限流计数失败: {}", e.getMessage());
            degradedFallback.adjust(key, delta);
        }
    }

    @Override
    public boolean isDegraded() {
        return degraded.get() || redis == null;
    }

    /**
     * 降级处理：使用**保守**的本地限额，而不是无限放行。
     * <p>
     * 计划要求"限流依赖故障采用有界降级，不能默认无限放行高成本接口"。
     * 因此降级时把限额收紧（例如取原值的 1/4），宁可误伤少量正常请求，
     * 也不让高成本接口在无保护状态下敞开。
     */
    private RateLimitDecision degrade(String key, int limit, int windowSeconds) {
        if (redis != null && degraded.compareAndSet(false, true)) {
            log.error("限流已降级为本地模式（Redis 不可用）；配额按实例计算，"
                    + "多实例下总配额会放大，请尽快恢复依赖");
        }
        return degradedFallback.tryAcquire(key, limited(limit, windowSeconds), windowSeconds);
    }

    private void markHealthy() {
        if (degraded.compareAndSet(true, false)) {
            log.info("限流依赖已恢复，回到 Redis 共享计数");
        }
    }

    /** 降级时的保守限额：至少为 1，且不超过原值的 1/4 */
    private int limited(int limit, int windowSeconds) {
        int tightened = Math.max(1, limit / 4);
        return Math.min(limit, tightened);
    }
}

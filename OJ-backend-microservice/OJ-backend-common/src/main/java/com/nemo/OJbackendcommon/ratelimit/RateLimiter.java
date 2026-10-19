package com.nemo.OJbackendcommon.ratelimit;

/**
 * 限流器。
 * <p>
 * 关键约束：**必须在多实例下生效**。进程内计数（Guava RateLimiter、AtomicInteger）
 * 在横向扩容后形同虚设——把实例数从 1 提到 3，实际配额就变成 3 倍。
 * 因此生产实现基于 Redis 共享计数，本地内存实现只作为降级手段。
 */
public interface RateLimiter {

    /**
     * 按滑动窗口计数并判定。
     *
     * @param key           维度键（如 {@code login:ip:1.2.3.4}）
     * @param limit         窗口内允许的次数
     * @param windowSeconds 窗口长度（秒）
     * @return 判定结果；被拒绝时带建议重试秒数
     */
    RateLimitDecision tryAcquire(String key, int limit, int windowSeconds);

    /**
     * 占用一个**配额**（如"未完成提交数"）。
     * <p>
     * 与 {@link #tryAcquire} 的区别很关键：限流统计的是"尝试次数"，
     * 被拒绝的尝试也应该计数（否则攻击者可以无限重试）；
     * 而配额统计的是"当前占用数"，被拒绝时**不能**计数——
     * 否则每次拒绝都会把计数推高一点，用户完成提交后释放的额度
     * 也填不平这些虚增，最终被自己从未真正占用的额度永久挡住。
     *
     * @return 成功占用返回 true；已达配额返回 false（不改变计数）
     */
    boolean tryOccupy(String key, int quota, int ttlSeconds);

    /**
     * 查询当前计数，不做累加。用于配额判断（如"未完成提交数"）。
     */
    long currentCount(String key);

    /**
     * 手动调整计数（如提交完成后释放一个未完成配额）。
     *
     * @param delta 正数增加、负数减少
     */
    void adjust(String key, long delta);

    /** 当前是否处于降级状态（依赖不可用） */
    boolean isDegraded();
}

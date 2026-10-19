package com.nemo.OJbackendcommon.ratelimit;

/**
 * 限流判定结果。
 * <p>
 * 带 {@code retryAfterSeconds} 是为了让客户端能识别并自行退避，
 * 而不是只拿到一句"请求过于频繁"就反复重试。
 */
public final class RateLimitDecision {

    private static final RateLimitDecision ALLOW = new RateLimitDecision(true, 0, null, 0);

    private final boolean allowed;

    /** 建议客户端多少秒后重试；allowed 为 true 时无意义 */
    private final long retryAfterSeconds;

    /** 触发的是哪条规则，便于排查与告警 */
    private final String rule;

    /** 当前窗口内已计数 */
    private final long currentCount;

    private RateLimitDecision(boolean allowed, long retryAfterSeconds, String rule, long currentCount) {
        this.allowed = allowed;
        this.retryAfterSeconds = retryAfterSeconds;
        this.rule = rule;
        this.currentCount = currentCount;
    }

    public static RateLimitDecision allow() {
        return ALLOW;
    }

    public static RateLimitDecision reject(long retryAfterSeconds, String rule, long currentCount) {
        return new RateLimitDecision(false, retryAfterSeconds, rule, currentCount);
    }

    public boolean isAllowed() {
        return allowed;
    }

    public long getRetryAfterSeconds() {
        return retryAfterSeconds;
    }

    public String getRule() {
        return rule;
    }

    public long getCurrentCount() {
        return currentCount;
    }

    @Override
    public String toString() {
        return allowed ? "ALLOW" : "REJECT(rule=" + rule + ", retryAfter=" + retryAfterSeconds + "s, count=" + currentCount + ")";
    }
}

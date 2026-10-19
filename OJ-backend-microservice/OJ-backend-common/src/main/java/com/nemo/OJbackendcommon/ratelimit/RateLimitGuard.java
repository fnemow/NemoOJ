package com.nemo.OJbackendcommon.ratelimit;

import com.nemo.OJbackendcommon.common.ErrorCode;
import com.nemo.OJbackendcommon.exception.BusinessException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import jakarta.servlet.http.HttpServletRequest;

/**
 * 业务侧限流入口。
 * <p>
 * 把"按什么维度限流、限额多少"集中在配置里，业务代码只表达意图
 * （例如"这是一次登录尝试"），避免限额散落在各处难以整体调整。
 * <p>
 * 拒绝时抛出带 {@code retryAfter} 的业务错误，客户端可据此退避。
 */
@Slf4j
@Component
public class RateLimitGuard {

    private final RateLimiter limiter;

    // ---- 登录 / 注册 ----
    @Value("${ratelimit.login.ip-limit:60}")
    private int loginIpLimit;

    @Value("${ratelimit.login.account-limit:20}")
    private int loginAccountLimit;

    @Value("${ratelimit.login.window-seconds:60}")
    private int loginWindowSeconds;

    @Value("${ratelimit.csrf.ip-limit:60}")
    private int csrfIpLimit;

    @Value("${ratelimit.csrf.window-seconds:60}")
    private int csrfWindowSeconds;

    // ---- 提交 ----
    @Value("${ratelimit.submit.user-limit:20}")
    private int submitUserLimit;

    @Value("${ratelimit.submit.ip-limit:60}")
    private int submitIpLimit;

    @Value("${ratelimit.submit.window-seconds:60}")
    private int submitWindowSeconds;

    /** 单用户未完成提交的并发配额 */
    @Value("${ratelimit.submit.pending-quota:8}")
    private int submitPendingQuota;

    /** 未完成提交配额的计数键存活时间，避免异常路径下键不过期 */
    @Value("${ratelimit.submit.pending-ttl-seconds:3600}")
    private int pendingTtlSeconds;

    public RateLimitGuard(RateLimiter limiter) {
        this.limiter = limiter;
    }

    // ---------- 登录 / 注册 ----------

    /**
     * 登录尝试限流：IP 与账号两个维度都要过。
     * <p>
     * 两个维度缺一不可——只限 IP 挡不住分布式撞库，只限账号挡不住
     * 用一个 IP 批量试不同账号。
     */
    public void checkLogin(HttpServletRequest request, String userAccount) {
        String ip = clientIp(request);
        enforce("login:ip:" + ip, loginIpLimit, loginWindowSeconds, "登录过于频繁，请稍后再试");
        if (userAccount != null && !userAccount.isEmpty()) {
            enforce("login:account:" + userAccount, loginAccountLimit, loginWindowSeconds,
                    "该账号登录尝试过多，请稍后再试");
        }
    }

    /** 注册限流：以 IP 为主（账号还不存在） */
    public void checkRegister(HttpServletRequest request) {
        String ip = clientIp(request);
        enforce("register:ip:" + ip, loginIpLimit, loginWindowSeconds, "注册过于频繁，请稍后再试");
    }

    /** Check before allocating a session, including for clients that discard cookies. */
    public void checkCsrf(HttpServletRequest request) {
        enforce("csrf:ip:" + clientIp(request), csrfIpLimit, csrfWindowSeconds,
                "安全令牌请求过于频繁，请稍后再试");
    }

    // ---------- 提交 ----------
    public void checkTryRun(HttpServletRequest request,long userId) {
        enforce("try-run:user:"+userId,3,60,"试运行过于频繁，请稍后再试");
        enforce("try-run:ip:"+clientIp(request),10,60,"当前网络试运行过于频繁，请稍后再试");
    }

    /**
     * 提交限流：用户与 IP 频率 + 未完成提交配额。
     * <p>
     * 频率限制挡"刷"，未完成配额挡"堆积"——后者才是把队列打满的主要途径：
     * 短时间内提交大量任务，每个都合法，但合起来会压垮判题容量。
     */
    public void checkSubmitRate(HttpServletRequest request, long userId) {
        String ip = clientIp(request);
        enforce("submit:user:" + userId, submitUserLimit, submitWindowSeconds,
                "提交过于频繁，请稍后再试");
        enforce("submit:ip:" + ip, submitIpLimit, submitWindowSeconds,
                "当前网络提交过于频繁，请稍后再试");
    }

    /** 请求内容通过校验且确定会新建判题任务后，占用一个未完成提交名额。 */
    public void occupySubmitPending(long userId) {
        // 未完成配额必须用 tryOccupy 而不是 tryAcquire：
        // 后者连"被拒绝的尝试"也计数，会让配额只增不减，用户最终被永久挡住。
        String pendingKey = pendingKey(userId);
        if (!limiter.tryOccupy(pendingKey, submitPendingQuota, pendingTtlSeconds)) {
            long pending = limiter.currentCount(pendingKey);
            throw new BusinessException(ErrorCode.OPERATION_ERROR,
                    "你还有 " + pending + " 个提交正在判题，请等待完成后再提交");
        }
    }

    /**
     * 释放一个未完成提交配额。任务进入终态时调用。
     * <p>
     * 不释放会导致配额只增不减，用户提交几次之后被永久挡住。
     */
    public void releasePending(long userId) {
        limiter.adjust(pendingKey(userId), -1);
    }

    private String pendingKey(long userId) {
        return "submit:pending:user:" + userId;
    }

    // ---------- 内部 ----------

    private void enforce(String key, int limit, int windowSeconds, String message) {
        RateLimitDecision decision = limiter.tryAcquire(key, limit, windowSeconds);
        if (!decision.isAllowed()) {
            log.warn("触发限流: {} ({})", decision, limiter.isDegraded() ? "降级模式" : "正常模式");
            throw new BusinessException(ErrorCode.OPERATION_ERROR,
                    message + "（约 " + decision.getRetryAfterSeconds() + " 秒后可重试）");
        }
    }

    /**
     * 取客户端 IP。
     * <p>
     * 只有**由可信反向代理注入**的 X-Forwarded-For 才能采信。直接读该头会让
     * 攻击者通过伪造头绕过 IP 限流。这里的策略是：默认只用 remoteAddr，
     * 仅当配置显式声明"部署在可信代理之后"时才取 XFF 的第一段。
     */
    @Value("${ratelimit.trust-forwarded-for:false}")
    private boolean trustForwardedFor;

    String clientIp(HttpServletRequest request) {
        if (trustForwardedFor) {
            String xff = request.getHeader("X-Forwarded-For");
            if (xff != null && !xff.isEmpty()) {
                int comma = xff.indexOf(',');
                String first = comma > 0 ? xff.substring(0, comma) : xff;
                String trimmed = first.trim();
                if (!trimmed.isEmpty()) {
                    return trimmed;
                }
            }
        }
        String remote = request.getRemoteAddr();
        return remote == null ? "unknown" : remote;
    }
}

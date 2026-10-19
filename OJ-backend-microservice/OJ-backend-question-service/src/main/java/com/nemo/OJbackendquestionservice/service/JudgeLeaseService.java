package com.nemo.OJbackendquestionservice.service;

import com.nemo.OJbackendmodel.model.entity.QuestionSubmit;

/**
 * 判题执行租约（T06）。
 * <p>
 * 提交状态的写操作集中在这里，统一强制"执行令牌 + 状态"双重校验。
 * 分散在控制器里逐个写 WHERE 条件太容易漏，漏一个就给了旧执行者覆盖结果的机会。
 * <p>
 * 对外状态值保持不变（0 待判题 / 1 判题中 / 2 成功 / 3 失败），
 * 本服务只负责内部执行控制。
 */
public interface JudgeLeaseService {

    /**
     * 原子抢占：仅当提交处于 WAITING 时成功，并生成新的执行令牌。
     *
     * @param leaseSeconds 租约时长
     * @return 新令牌；null 表示抢占失败
     */
    String claim(long submitId, int leaseSeconds);

    /**
     * 续租。仅当状态仍为 UNDER_JUDGING 且令牌匹配时生效。
     *
     * @return false 表示租约已失效（被回收或已完成），调用方应停止写回
     */
    boolean renewLease(long submitId, String token, int leaseSeconds);

    /**
     * 写回终态结果。仅当状态仍为 UNDER_JUDGING 且令牌匹配时生效。
     * <p>
     * 这条校验是"禁止旧执行迟到结果覆盖新执行或已完成结果"的落点：
     * 被回收重投后令牌已变，旧执行者的写入会返回 false 而不是覆盖。
     */
    boolean finish(long submitId, String token, Integer status, String judgeInfoJson);

    /**
     * 执行异常回退到 WAITING，并记录原因。令牌匹配才生效。
     */
    boolean revertToWaiting(long submitId, String token, String reason);

    /**
     * 死信兜底：把仍处于待判题的提交判为系统错误终态。
     *
     * @return false 表示提交已被处理（终态或正在执行），本次无需改动
     */
    boolean markFailedIfWaiting(long submitId, String reason);

    /**
     * 回收过期租约：把已失联的执行重新排入待判题，或在其执行次数超限时判为系统错误终态。
     * <p>
     * 由独立回收器调用，不依赖消费者自身是否存活——消费者进程被杀时，
     * 只有独立回收器能接管。
     *
     * @return 本次回收处理的提交数
     */
    int reclaimExpired(int batchSize);

    QuestionSubmit getById(long submitId);
}

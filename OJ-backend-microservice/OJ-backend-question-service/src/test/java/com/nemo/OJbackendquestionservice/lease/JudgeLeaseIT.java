package com.nemo.OJbackendquestionservice.lease;

import com.nemo.OJbackendmodel.model.entity.QuestionSubmit;
import com.nemo.OJbackendmodel.model.enums.QuestionSubmitStatusEnum;
import com.nemo.OJbackendquestionservice.service.JudgeLeaseService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 判题执行租约集成测试（需要真实 MySQL，且已执行 deploy/migration）。
 * <p>
 * 覆盖 T06 的四条关键验收：
 * <ul>
 *   <li>原子抢占：并发下只有一个执行者拿到令牌</li>
 *   <li>旧执行迟到写回被拒绝（令牌不匹配）</li>
 *   <li>终态不可被覆盖</li>
 *   <li>过期租约被回收并重新排队（含执行次数上限）</li>
 * </ul>
 */
@SpringBootTest
@ActiveProfiles("it")
class JudgeLeaseIT {

    private static final int WAITING = QuestionSubmitStatusEnum.WAITING_FOR_JUDGING.getValue();
    private static final int JUDGING = QuestionSubmitStatusEnum.UNDER_JUDGING.getValue();
    private static final int FAILED = QuestionSubmitStatusEnum.FAILED.getValue();

    @Autowired
    private JudgeLeaseService judgeLeaseService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private long newSubmit(long id, int status, int attemptCount, Integer leaseSecondsAgo) {
        jdbcTemplate.update("DELETE FROM question_submit WHERE id = ?", id);
        jdbcTemplate.update(
                "INSERT INTO question_submit(id, language, code, status, attemptCount, questionId, userId) "
                        + "VALUES (?, 'java', 'class Main{}', ?, ?, 1, 1)", id, status, attemptCount);
        if (leaseSecondsAgo != null) {
            // 模拟租约已过期（负数表示已过期多少秒）
            jdbcTemplate.update("UPDATE question_submit SET leaseExpireAt = DATE_SUB(NOW(), INTERVAL ? SECOND) "
                    + "WHERE id = ?", leaseSecondsAgo, id);
        }
        return id;
    }

    private QuestionSubmit reload(long id) {
        return judgeLeaseService.getById(id);
    }

    private void cleanup(long id) {
        jdbcTemplate.update("DELETE FROM outbox_event WHERE bizId = ?", id);
        jdbcTemplate.update("DELETE FROM question_submit WHERE id = ?", id);
    }

    // ---------- 原子抢占 ----------

    @Test
    @DisplayName("抢占成功一次，第二次抢占失败（同一提交只有一个执行者）")
    void claimIsAtomic() {
        long id = newSubmit(991001L, WAITING, 0, null);
        try {
            String token1 = judgeLeaseService.claim(id, 60);
            assertNotNull(token1, "首次抢占应成功");

            String token2 = judgeLeaseService.claim(id, 60);
            assertNull(token2, "已被抢占，第二次必须失败");

            QuestionSubmit after = reload(id);
            assertEquals(JUDGING, after.getStatus());
            assertEquals(token1, after.getExecutionToken(), "令牌应为首次抢占生成的那个");
            assertEquals(1, after.getAttemptCount(), "执行次数应只累加一次");
        } finally {
            cleanup(id);
        }
    }

    // ---------- 迟到写回 ----------

    @Test
    @DisplayName("旧执行的迟到写回被拒绝，不覆盖新一轮执行")
    void staleTokenWriteRejected() {
        long id = newSubmit(991002L, WAITING, 0, null);
        try {
            String oldToken = judgeLeaseService.claim(id, 60);
            assertNotNull(oldToken);

            // 模拟租约过期被回收 → 重新排队 → 新执行者抢占
            jdbcTemplate.update("UPDATE question_submit SET status = 0, executionToken = NULL, "
                    + "leaseExpireAt = NULL WHERE id = ?", id);
            String newToken = judgeLeaseService.claim(id, 60);
            assertNotNull(newToken, "回收后应能重新抢占");

            // 旧执行者迟到写回：必须被拒绝
            boolean stale = judgeLeaseService.finish(id, oldToken, FAILED,
                    "{\"message\":\"Runtime Error\"}");
            assertFalse(stale, "旧令牌的写回必须被拒绝");
            assertEquals(JUDGING, reload(id).getStatus(), "状态不应被旧执行改变");

            // 新执行者写回：应当成功
            boolean fresh = judgeLeaseService.finish(id, newToken, FAILED,
                    "{\"message\":\"Runtime Error\"}");
            assertTrue(fresh, "当前令牌的写回应成功");
            assertEquals(FAILED, reload(id).getStatus());
        } finally {
            cleanup(id);
        }
    }

    @Test
    @DisplayName("终态结果不会被重复写回改变")
    void terminalStateNotOverwritten() {
        long id = newSubmit(991003L, WAITING, 0, null);
        try {
            String token = judgeLeaseService.claim(id, 60);
            assertTrue(judgeLeaseService.finish(id, token, 2, "{\"message\":\"Accepted\"}"),
                    "首次写回应成功");

            // 重复写回（模拟重复消息/重复死信）
            assertFalse(judgeLeaseService.finish(id, token, FAILED, "{\"message\":\"Wrong Answer\"}"),
                    "已完成的结果不应被覆盖");
            assertEquals(2, reload(id).getStatus(), "状态应保持 Accepted");
        } finally {
            cleanup(id);
        }
    }

    @Test
    @DisplayName("死信兜底只影响待判题提交，不动正在执行与已完成")
    void markFailedOnlyAffectsWaiting() {
        long waiting = newSubmit(991004L, WAITING, 0, null);
        long judging = newSubmit(991005L, JUDGING, 1, null);
        long done = newSubmit(991006L, 2, 1, null);
        try {
            assertTrue(judgeLeaseService.markFailedIfWaiting(waiting, "死信"), "待判题应被标记失败");
            assertEquals(FAILED, reload(waiting).getStatus());

            assertFalse(judgeLeaseService.markFailedIfWaiting(judging, "死信"),
                    "正在执行的提交不应被死信误杀");
            assertEquals(JUDGING, reload(judging).getStatus());

            assertFalse(judgeLeaseService.markFailedIfWaiting(done, "死信"),
                    "已完成的提交不应被死信覆盖");
            assertEquals(2, reload(done).getStatus());
        } finally {
            cleanup(waiting);
            cleanup(judging);
            cleanup(done);
        }
    }

    // ---------- 续租与回收 ----------

    @Test
    @DisplayName("续租：正确令牌成功，错误令牌失败")
    void renewLeaseRequiresCorrectToken() {
        long id = newSubmit(991007L, WAITING, 0, null);
        try {
            String token = judgeLeaseService.claim(id, 60);
            assertTrue(judgeLeaseService.renewLease(id, token, 60), "正确令牌应能续租");
            assertFalse(judgeLeaseService.renewLease(id, "wrong-token", 60), "错误令牌不能续租");
        } finally {
            cleanup(id);
        }
    }

    @Test
    @DisplayName("过期租约被回收：退回待判题并重新生成待投递事件")
    void reclaimsExpiredLeaseAndRequeues() {
        // 租约已过期 10 秒，执行次数未达上限
        long id = newSubmit(991008L, JUDGING, 1, 10);
        try {
            jdbcTemplate.update("UPDATE question_submit SET executionToken = 'stale-token' WHERE id = ?", id);

            int handled = judgeLeaseService.reclaimExpired(50);
            assertTrue(handled >= 1, "应至少回收一条");

            QuestionSubmit after = reload(id);
            assertEquals(WAITING, after.getStatus(), "应退回待判题");
            assertNull(after.getExecutionToken(), "令牌应被清空");

            Long events = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM outbox_event WHERE bizId = ? AND eventType = 'JUDGE_SUBMIT'",
                    Long.class, id);
            assertNotNull(events);
            assertEquals(1L, events, "必须重新生成待投递事件，否则任务只是躺在库里没人处理");
        } finally {
            cleanup(id);
        }
    }

    @Test
    @DisplayName("长任务不会被误回收：租约未过期时不回收")
    void doesNotReclaimActiveLease() {
        long id = newSubmit(991009L, WAITING, 0, null);
        try {
            String token = judgeLeaseService.claim(id, 120);
            assertNotNull(token);
            // 刚续租，租约在未来
            judgeLeaseService.reclaimExpired(50);
            assertEquals(JUDGING, reload(id).getStatus(), "租约有效期内不应被回收");
            assertEquals(token, reload(id).getExecutionToken(), "令牌不应被改动");
        } finally {
            cleanup(id);
        }
    }

    @Test
    @DisplayName("执行次数超上限的过期任务进入系统错误终态，不再重投")
    void exhaustedAttemptsBecomeTerminal() {
        // 执行次数已达上限（默认 max-attempts=3）
        long id = newSubmit(991010L, JUDGING, 3, 10);
        try {
            jdbcTemplate.update("UPDATE question_submit SET executionToken = 'stale-token' WHERE id = ?", id);

            judgeLeaseService.reclaimExpired(50);

            QuestionSubmit after = reload(id);
            assertEquals(FAILED, after.getStatus(), "超过执行次数上限应判为失败终态");
            assertNull(after.getExecutionToken());

            Long events = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM outbox_event WHERE bizId = ?", Long.class, id);
            assertNotNull(events);
            assertEquals(0L, events, "终态不应再产生待投递事件，避免无限重投");
        } finally {
            cleanup(id);
        }
    }
}

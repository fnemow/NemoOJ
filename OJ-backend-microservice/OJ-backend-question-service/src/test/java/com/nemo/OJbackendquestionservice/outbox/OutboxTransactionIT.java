package com.nemo.OJbackendquestionservice.outbox;

import com.nemo.OJbackendmodel.model.dto.questionSubmit.QuestionSubmitAddRequest;
import com.nemo.OJbackendmodel.model.entity.Question;
import com.nemo.OJbackendmodel.model.entity.User;
import com.nemo.OJbackendquestionservice.mapper.OutboxEventMapper;
import com.nemo.OJbackendquestionservice.service.QuestionService;
import com.nemo.OJbackendquestionservice.service.QuestionSubmitService;
import com.nemo.OJserviceclient.JudgeFeignClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.transaction.TestTransaction;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

/**
 * 发件箱事务性集成测试（需要真实 MySQL，且已执行 deploy/migration）。
 * <p>
 * 对应 T05 验收的核心一条：<b>提交事务回滚时没有可投递事件</b>。
 * 这条约束如果破了，就会重新出现"提交没成功、但消息已经发出去了"的不一致。
 */
@SpringBootTest
@ActiveProfiles("it")
class OutboxTransactionIT {

    // This suite exercises real DB transactions, not a deployed judge runtime.
    // Keep the remote capability boundary deterministic without mocking writes.
    @MockitoBean
    private JudgeFeignClient judgeFeignClient;

    @BeforeEach
    void readyJavaRuntime() {
        when(judgeFeignClient.capabilities()).thenReturn(List.of(Map.of(
                "language", "java", "imageReady", true, "modes", List.of("ACM"))));
    }

    @Autowired
    private QuestionSubmitService questionSubmitService;

    @Autowired
    private QuestionService questionService;

    @Autowired
    private OutboxEventMapper outboxEventMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private long countSubmits(long questionId) {
        Long n = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM question_submit WHERE questionId = ?", Long.class, questionId);
        return n == null ? 0 : n;
    }

    private long countEventsFor(long submitId) {
        Long n = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM outbox_event WHERE bizId = ? AND eventType = 'JUDGE_SUBMIT'",
                Long.class, submitId);
        return n == null ? 0 : n;
    }

    private QuestionSubmitAddRequest buildRequest(long questionId) {
        QuestionSubmitAddRequest req = new QuestionSubmitAddRequest();
        req.setQuestionId(questionId);
        req.setLanguage("java");
        req.setCode("public class Main { public static void main(String[] a){} }");
        return req;
    }

    private Question newQuestion(String prefix) {
        Question question = new Question();
        question.setTitle(prefix + "-" + System.nanoTime());
        question.setUserId(1L);
        question.setIsPublished(true);
        questionService.save(question);
        return question;
    }

    private static User user() {
        User user = new User();
        user.setId(1L);
        user.setUserAccount("it-outbox-user");
        return user;
    }

    /**
     * 关键测试：在事务内提交，然后**主动结束并回滚该事务**，再回到事务外验证。
     * <p>
     * 若 OutboxService 使用了 REQUIRES_NEW（独立事务），事件会在业务事务回滚后**仍然存在**，
     * 本测试就会失败。这是刻意的：它守住的正是"同库同事务"这一要求。
     * <p>
     * 用 {@link TestTransaction#end()} 而不是依赖测试结束后的自动回滚，
     * 是为了让断言发生在事务之外、且不依赖测试执行顺序。
     */
    @Test
    @DisplayName("事务回滚时不留下提交记录，也不留下可投递事件")
    @Transactional
    void rollbackLeavesNeitherSubmitNorEvent() {
        Question question = newQuestion("it-outbox-rollback");
        long questionId = question.getId();

        long submitId = questionSubmitService.doQuestionSubmit(buildRequest(questionId), user());

        // 事务内可见
        assertEquals(1, countSubmits(questionId), "事务内应能看到提交记录");
        assertEquals(1, countEventsFor(submitId), "事务内应能看到待投递事件");

        // 结束并回滚测试事务，之后的查询走自动提交，看到的是真实落库状态
        TestTransaction.flagForRollback();
        TestTransaction.end();

        assertEquals(0, countSubmits(questionId), "回滚后不应残留提交记录");
        assertEquals(0, countEventsFor(submitId), "回滚后不应残留可投递事件");
    }

    @Test
    @DisplayName("提交成功后事件与提交同时存在，且事件初始为待投递")
    @Transactional
    void commitCreatesBothRowAndPendingEvent() {
        Question question = newQuestion("it-outbox-commit");
        long questionId = question.getId();

        long submitId = questionSubmitService.doQuestionSubmit(buildRequest(questionId), user());

        try {
            assertEquals(1, countSubmits(questionId));
            assertEquals(1, countEventsFor(submitId), "提交成功时事件必须同时存在");

            OutboxEvent event = outboxEventMapper.selectOne(
                    new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<OutboxEvent>()
                            .eq("bizId", submitId)
                            .eq("eventType", "JUDGE_SUBMIT"));
            assertNotNull(event);
            assertEquals(submitId, event.getBizId(), "事件必须记录业务 id 以便对账");
            assertEquals(0, event.getRetryCount());
            assertEquals(OutboxStatus.PENDING, event.getStatus(), "新事件应为待投递");
            assertEquals(String.valueOf(submitId), event.getPayload(), "消息体应为提交 id");

            // Inspect the initial state before commit: the real AFTER_COMMIT
            // dispatcher may claim it immediately once it becomes visible.
            // Then verify durability outside the transaction, without assuming
            // that the background worker has not run yet.
            TestTransaction.flagForCommit();
            TestTransaction.end();
            assertEquals(1, countSubmits(questionId), "提交记录必须真实提交到数据库");
            assertEquals(1, countEventsFor(submitId), "事件必须与提交记录一起持久化");
        } finally {
            // 显式提交后清理测试数据；提交前断言失败时由测试事务回滚兜底
            jdbcTemplate.update("DELETE FROM outbox_event WHERE bizId = ?", submitId);
            jdbcTemplate.update("DELETE FROM question_submit WHERE questionId = ?", questionId);
            jdbcTemplate.update("DELETE FROM question WHERE id = ?", questionId);
        }
    }

    @Test
    @DisplayName("不存在没有对应提交的孤儿事件")
    void noOrphanOutboxEvents() {
        List<java.util.Map<String, Object>> orphans = jdbcTemplate.queryForList(
                "SELECT e.id, e.eventId, e.bizId FROM outbox_event e WHERE e.eventType = 'JUDGE_SUBMIT' "
                        + "AND NOT EXISTS (SELECT 1 FROM question_submit s WHERE s.id = e.bizId)");
        assertTrue(orphans.isEmpty(),
                "不应存在没有对应提交的事件（说明事件被提交到了独立事务）: " + orphans);
    }
}

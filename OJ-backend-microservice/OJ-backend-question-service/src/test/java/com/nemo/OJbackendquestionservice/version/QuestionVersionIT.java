package com.nemo.OJbackendquestionservice.version;

import com.nemo.OJbackendmodel.model.dto.question.JudgeCase;
import com.nemo.OJbackendmodel.model.entity.Question;
import com.nemo.OJbackendquestionservice.service.QuestionService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 题目不可变版本快照集成测试（需要真实 MySQL，且已执行 deploy/migration）。
 * <p>
 * 覆盖 T07 的关键验收：
 * <ul>
 *   <li>发布生成不可变快照；改题不影响已发布版本</li>
 *   <li>提交绑定的版本能取回**当时的**判题依据（而不是当前题目）</li>
 *   <li>并发发布不产生重复版本号</li>
 * </ul>
 */
@SpringBootTest
@ActiveProfiles("it")
class QuestionVersionIT {

    @Autowired
    private QuestionService questionService;

    @Autowired
    private QuestionVersionService questionVersionService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private Question newQuestion(String title, String caseOutput) {
        Question q = new Question();
        q.setTitle(title);
        q.setContent("content");
        q.setTags("[\"tag\"]");
        q.setAnswer("参考答案");
        q.setUserId(1L);
        q.setIsPublished(false);
        JudgeCase jc = new JudgeCase();
        jc.setInput("1 2");
        jc.setOutput(caseOutput);
        q.setJudgeCase(new ArrayList<>(Collections.singletonList(jc)));
        questionService.save(q);
        return q;
    }

    private void cleanup(long questionId) {
        jdbcTemplate.update("DELETE FROM question_version WHERE questionId = ?", questionId);
        jdbcTemplate.update("DELETE FROM question WHERE id = ?", questionId);
    }

    // ---------- 不可变性 ----------

    @Test
    @DisplayName("发布生成版本快照；改题不影响已发布版本，再次发布产生新版本")
    void publishedVersionIsImmutable() {
        Question q = newQuestion("it-ver-immutable-" + System.nanoTime(), "3");
        long qid = q.getId();
        try {
            int v1 = questionVersionService.publish(q, 1L);
            assertEquals(1, v1, "首次发布应为版本 1");

            // 修改当前题目（模拟"编辑并再次发布"）
            Question edited = new Question();
            edited.setId(qid);
            edited.setTitle("改过的标题");
            JudgeCase changed = new JudgeCase();
            changed.setInput("1 2");
            changed.setOutput("999");
            edited.setJudgeCase(new ArrayList<>(Collections.singletonList(changed)));
            questionService.updateById(edited);

            // 已发布版本必须保持原样
            Question frozen = questionVersionService.asQuestion(qid, v1);
            assertNotNull(frozen);
            assertEquals("3", frozen.getJudgeCase().get(0).getOutput(),
                    "已发布版本的判题用例不应被后续编辑改变");
            assertEquals(q.getTitle(), frozen.getTitle(), "已发布版本的标题不应被改变");

            // 再次发布产生新版本，新版本才反映改动
            Question current = questionService.getById(qid);
            int v2 = questionVersionService.publish(current, 1L);
            assertEquals(2, v2, "再次发布应产生版本 2");

            Question frozen2 = questionVersionService.asQuestion(qid, v2);
            assertNotNull(frozen2);
            assertEquals("999", frozen2.getJudgeCase().get(0).getOutput(),
                    "新版本应反映编辑后的内容");

            // 旧版本依然可取回原内容
            Question stillOld = questionVersionService.asQuestion(qid, v1);
            assertEquals("3", stillOld.getJudgeCase().get(0).getOutput(),
                    "新版本的产生不影响旧版本");
        } finally {
            cleanup(qid);
        }
    }

    @Test
    @DisplayName("不存在的版本返回 null（历史提交不可伪造快照）")
    void missingVersionReturnsNull() {
        assertNull(questionVersionService.asQuestion(999999999L, 1),
                "没有版本行时必须返回 null，而不是伪造一个快照");
    }

    @Test
    @DisplayName("快照保留答案与隐藏用例，仅内部接口可取用")
    void snapshotKeepsAnswerAndCases() {
        Question q = newQuestion("it-ver-answer-" + System.nanoTime(), "42");
        long qid = q.getId();
        try {
            int v = questionVersionService.publish(q, 1L);
            QuestionVersion snapshot = questionVersionService.getVersion(qid, v);
            assertNotNull(snapshot);
            assertEquals("参考答案", snapshot.getAnswer(), "快照需保留参考答案供判题");
            assertNotNull(snapshot.getJudgeCase());
            assertEquals(1, snapshot.getJudgeCase().size(), "快照需保留隐藏用例");
        } finally {
            cleanup(qid);
        }
    }

    // ---------- 并发发布 ----------

    @Test
    @DisplayName("并发发布不产生冲突版本：版本号唯一且连续分配")
    void concurrentPublishProducesDistinctVersions() throws Exception {
        Question q = newQuestion("it-ver-concurrent-" + System.nanoTime(), "1");
        long qid = q.getId();
        int threads = 6;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch gate = new CountDownLatch(1);
        Set<Integer> versions = ConcurrentHashMap.newKeySet();
        List<String> errors = Collections.synchronizedList(new ArrayList<>());

        try {
            List<java.util.concurrent.Callable<Void>> tasks = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                tasks.add(() -> {
                    gate.await();
                    try {
                        Question current = questionService.getById(qid);
                        versions.add(questionVersionService.publish(current, 1L));
                    } catch (Exception e) {
                        errors.add(e.getClass().getSimpleName() + ": " + e.getMessage());
                    }
                    return null;
                });
            }
            List<java.util.concurrent.Future<Void>> futures = new ArrayList<>();
            for (java.util.concurrent.Callable<Void> t : tasks) {
                futures.add(pool.submit(t));
            }
            gate.countDown();
            for (java.util.concurrent.Future<Void> f : futures) {
                f.get(60, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }

        assertTrue(errors.isEmpty(), "并发发布不应报错: " + errors);
        assertEquals(threads, versions.size(),
                "每次发布都应拿到不同的版本号，实际: " + versions);
        assertEquals(threads, questionVersionService.currentVersion(qid),
                "库中版本数应与发布次数一致");

        cleanup(qid);
    }
}

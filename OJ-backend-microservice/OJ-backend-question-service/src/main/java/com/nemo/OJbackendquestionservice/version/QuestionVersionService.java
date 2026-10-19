package com.nemo.OJbackendquestionservice.version;

import com.nemo.OJbackendmodel.model.entity.Question;
import com.nemo.OJbackendquestionservice.mapper.QuestionVersionMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.util.Date;

/**
 * 题目版本快照服务。
 * <p>
 * 职责是把"当前题目"固化成一行不可变快照，并返回版本号。
 * 发布之后的任何编辑都不会影响已生成的快照——这正是历史提交可复现的前提。
 */
@Slf4j
@Service
public class QuestionVersionService {

    /**
     * 并发发布时的重试上限。
     * <p>
     * 冲突来自唯一键 uk_question_version。最坏情况下有 N 个发布者同时竞争，
     * 每个线程可能需要最多 N 次尝试才能拿到唯一版本号，因此上限按
     * "预期并发发布者数量" 设定并留出余量；仍然是**有界**的，不会无限重试。
     * <p>
     * 版本号的唯一性由数据库唯一键保证，重试只是让并发请求最终都能成功，
     * 而不是正确性的来源。
     */
    private static final int MAX_PUBLISH_ATTEMPTS = 20;

    private final QuestionVersionMapper questionVersionMapper;

    public QuestionVersionService(QuestionVersionMapper questionVersionMapper) {
        this.questionVersionMapper = questionVersionMapper;
    }

    /**
     * 为当前题目生成一个新的不可变版本快照。
     *
     * @return 新版本号
     * @throws IllegalStateException 重试耗尽仍冲突（正常不会发生）
     */
    public int publish(Question question, Long publisherId) {
        for (int attempt = 1; attempt <= MAX_PUBLISH_ATTEMPTS; attempt++) {
            int existingMax = questionVersionMapper.maxVersion(question.getId());
            int requestedVersion = question.getProblemVersion() == null ? 1 : question.getProblemVersion();
            int nextVersion = existingMax == 0 ? requestedVersion : existingMax + 1;
            QuestionVersion snapshot = snapshotOf(question, publisherId);
            snapshot.setVersion(nextVersion);
            try {
                questionVersionMapper.insert(snapshot);
                log.info("题目 {} 已发布版本 {}", question.getId(), nextVersion);
                return nextVersion;
            } catch (DuplicateKeyException e) {
                // 并发的另一次发布抢先占用了同一个版本号。唯一键 uk_question_version
                // 挡住了重复版本，这里重新计算版本号再试即可。
                log.warn("发布版本号冲突，第 {}/{} 次重试, questionId={}, 冲突版本={}",
                        attempt, MAX_PUBLISH_ATTEMPTS, question.getId(), nextVersion);
                // 加入随机退避，打散同时重试的请求，避免反复撞在一起
                sleepJitter();
            }
        }
        throw new IllegalStateException(
                "题目 " + question.getId() + " 发布失败：版本号冲突重试 " + MAX_PUBLISH_ATTEMPTS + " 次仍未成功");
    }

    /**
     * 读取某版本的快照；不存在返回 null。
     * 判题只使用本方法的结果，绝不回退到"当前题目"。
     */
    public QuestionVersion getVersion(long questionId, int version) {
        return questionVersionMapper.selectOne(
                new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<QuestionVersion>()
                        .eq("questionId", questionId)
                        .eq("version", version));
    }

    /**
     * 把版本快照还原成 {@link Question}，供判题使用。
     * <p>
     * 返回的字段只来自快照，**不会**混入当前题目的任何内容——
     * 混入一处就足以让"历史提交按新数据重判"重新出现。
     *
     * @return 快照对应的题目对象；版本不存在时返回 null
     */
    public Question asQuestion(long questionId, int version) {
        QuestionVersion v = getVersion(questionId, version);
        if (v == null) {
            return null;
        }
        Question q = new Question();
        q.setId(questionId);
        q.setTitle(v.getTitle());
        q.setContent(v.getContent());
        q.setTags(v.getTags());
        q.setAnswer(v.getAnswer());
        q.setJudgeCase(v.getJudgeCase());
        q.setJudgeConfig(v.getJudgeConfig());
        q.setProblemSpec(v.getProblemSpec());
        q.setProblemVersion(v.getVersion());
        q.setIsPublished(true);
        q.setUserId(v.getPublishedBy());
        q.setCreateTime(v.getCreateTime());
        return q;
    }

    /** 冲突重试之间的随机退避（1~20ms），打散并发重试 */
    private void sleepJitter() {
        try {
            Thread.sleep(1 + java.util.concurrent.ThreadLocalRandom.current().nextInt(20));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** 当前最大版本号 */
    public int currentVersion(long questionId) {
        return questionVersionMapper.maxVersion(questionId);
    }

    private QuestionVersion snapshotOf(Question question, Long publisherId) {
        QuestionVersion v = new QuestionVersion();
        v.setQuestionId(question.getId());
        v.setTitle(question.getTitle());
        v.setContent(question.getContent());
        v.setTags(question.getTags());
        v.setAnswer(question.getAnswer());
        // 直接带对象写入：QuestionVersion 使用与 Question 相同的 JacksonTypeHandler，
        // 序列化方式一致，避免"快照与原始数据 JSON 格式不同"导致判题解析失败。
        v.setJudgeCase(question.getJudgeCase());
        v.setJudgeConfig(question.getJudgeConfig());
        v.setProblemSpec(question.getProblemSpec());
        if (question.getProblemSpec() != null) {
            v.setHarnessVersion(question.getProblemSpec().getHarnessVersion());
        }
        v.setPublishedBy(publisherId);
        v.setCreateTime(new Date());
        return v;
    }
}
